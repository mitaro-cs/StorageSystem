package app.groupbase.avatars;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class LoginBackgroundTest {

  @Test
  void bigPicturesShrinkKeepingProportions() {
    BufferedImage wide = new BufferedImage(4000, 1000, BufferedImage.TYPE_INT_RGB);
    BufferedImage out = LoginBackground.fit(wide, 1920);
    assertThat(out.getWidth()).isEqualTo(1920);
    assertThat(out.getHeight()).isEqualTo(480);
    BufferedImage tall = new BufferedImage(900, 3000, BufferedImage.TYPE_INT_RGB);
    assertThat(LoginBackground.fit(tall, 1920).getHeight()).isEqualTo(1920);
    assertThat(LoginBackground.fit(tall, 1920).getWidth()).isEqualTo(576);
  }

  @Test
  void smallPicturesAreNotUpscaled() {
    BufferedImage small = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
    BufferedImage out = LoginBackground.fit(small, 1920);
    assertThat(out.getWidth()).isEqualTo(640);
    assertThat(out.getHeight()).isEqualTo(480);
  }

  @Test
  void onlyKnownValuesAreValid() {
    assertThat(LoginBackground.valid("none")).isTrue();
    assertThat(LoginBackground.valid("preset:aurora")).isTrue();
    assertThat(LoginBackground.valid("image:0123456789abcdef0123")).isTrue();
    assertThat(LoginBackground.valid("preset:../x")).isFalse();
    assertThat(LoginBackground.valid("image:../../secrets")).isFalse();
    assertThat(LoginBackground.valid("https://example.com/x.png")).isFalse();
  }
}
