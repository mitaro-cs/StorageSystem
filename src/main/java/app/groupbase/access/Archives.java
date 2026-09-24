package app.groupbase.access;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Достать один файл из .zip или .tar.gz (клиенты туннелей раздаются архивами). */
final class Archives {

  private Archives() {}

  static void extractZip(InputStream in, String entry, Path target) throws IOException {
    try (ZipInputStream zip = new ZipInputStream(in)) {
      for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
        if (!e.isDirectory() && baseName(e.getName()).equals(entry)) {
          Files.copy(zip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
          return;
        }
      }
    }
    throw new IOException("В архиве нет " + entry);
  }

  /** Минимальный tar: заголовки по 512 байт, размер — восьмеричный, данные выровнены по 512. */
  static void extractTarGz(InputStream in, String entry, Path target) throws IOException {
    try (InputStream tar = new GZIPInputStream(in)) {
      byte[] header = new byte[512];
      while (true) {
        if (!readFully(tar, header)) {
          break;
        }
        boolean empty = true;
        for (byte b : header) {
          if (b != 0) {
            empty = false;
            break;
          }
        }
        if (empty) {
          break;
        }
        String name = field(header, 0, 100);
        String prefix = field(header, 345, 155);
        if (!prefix.isEmpty()) {
          name = prefix + "/" + name;
        }
        long size =
            Long.parseLong(
                field(header, 124, 12).trim().isEmpty() ? "0" : field(header, 124, 12).trim(), 8);
        char type = (char) header[156];
        long padded = (size + 511) / 512 * 512;
        if ((type == '0' || type == 0) && baseName(name).equals(entry)) {
          try (OutputStream out = Files.newOutputStream(target)) {
            copy(tar, out, size);
          }
          return;
        }
        skip(tar, padded);
      }
    }
    throw new IOException("В архиве нет " + entry);
  }

  private static String baseName(String name) {
    String n = name.replace('\\', '/');
    int slash = n.lastIndexOf('/');
    return slash < 0 ? n : n.substring(slash + 1);
  }

  private static String field(byte[] h, int off, int len) {
    int end = off;
    while (end < off + len && h[end] != 0) {
      end++;
    }
    return new String(h, off, end - off, StandardCharsets.UTF_8);
  }

  private static boolean readFully(InputStream in, byte[] buf) throws IOException {
    int n = 0;
    while (n < buf.length) {
      int r = in.read(buf, n, buf.length - n);
      if (r < 0) {
        if (n == 0) {
          return false;
        }
        throw new EOFException("Архив оборван");
      }
      n += r;
    }
    return true;
  }

  private static void copy(InputStream in, OutputStream out, long size) throws IOException {
    byte[] buf = new byte[64 * 1024];
    long left = size;
    while (left > 0) {
      int r = in.read(buf, 0, (int) Math.min(buf.length, left));
      if (r < 0) {
        throw new EOFException("Архив оборван");
      }
      out.write(buf, 0, r);
      left -= r;
    }
  }

  private static void skip(InputStream in, long n) throws IOException {
    long left = n;
    while (left > 0) {
      long s = in.skip(left);
      if (s <= 0) {
        if (in.read() < 0) {
          throw new EOFException("Архив оборван");
        }
        s = 1;
      }
      left -= s;
    }
  }
}
