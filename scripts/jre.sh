#!/bin/sh
# Минимальная Java для приложения (jlink): только нужные модули, русская локаль.
#
#   scripts/jre.sh <каталог>                 — для этой же системы (Java из JAVA_HOME или PATH)
#   JMODS=<jdk>/jmods scripts/jre.sh <каталог> — для другой системы: jmods целевого JDK той же версии
#
# Список модулей — по jdeps для target/groupbase.jar плюс то, что jdeps не видит
# (криптография EC, русские даты, кодировки).
set -eu

OUT=${1:?Укажите каталог для рантайма}
# Git Bash на Windows: JAVA_HOME вида C:\…  — переводим в путь, понятный оболочке.
if [ -n "${JAVA_HOME:-}" ] && command -v cygpath >/dev/null 2>&1; then
	JAVA_HOME=$(cygpath -u "$JAVA_HOME")
fi
if [ -n "${JAVA_HOME:-}" ]; then
	JLINK="$JAVA_HOME/bin/jlink"
else
	JLINK=$(command -v jlink)
fi
JMODS=${JMODS:-$(dirname "$(dirname "$JLINK")")/jmods}

MODULES="java.base java.compiler java.desktop java.instrument java.management java.naming
java.net.http java.prefs java.scripting java.security.jgss java.sql java.sql.rowset jdk.jfr
jdk.unsupported jdk.crypto.ec jdk.localedata jdk.charsets jdk.zipfs"

# В новых JDK часть модулей слита в java.base (jdk.crypto.ec с 22) — берём только существующие.
LIST=""
for m in $MODULES; do
	if [ ! -d "$JMODS" ] || [ -f "$JMODS/$m.jmod" ]; then
		LIST="${LIST:+$LIST,}$m"
	fi
done

rm -rf "$OUT"
set -- --add-modules "$LIST" --output "$OUT" --strip-debug --no-man-pages --no-header-files \
	--include-locales=en,ru --compress=zip-6
if [ -d "$JMODS" ]; then
	set -- --module-path "$JMODS" "$@"
fi
"$JLINK" "$@"
# jlink делает часть файлов (лицензии) только для чтения — сборщику приложения нужно их трогать.
chmod -R u+w "$OUT"
echo "Рантайм: $OUT ($(du -sh "$OUT" | cut -f1))"
