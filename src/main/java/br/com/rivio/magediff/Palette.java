package br.com.rivio.magediff;

import java.awt.Color;
import javax.swing.UIManager;

/**
 * Cores do diff. Duas paletas escolhidas pela luminância do fundo do
 * look-and-feel: no macOS em modo escuro o Aqua já pinta os controles escuros, e
 * um painel de diff claro no meio disso fica ilegível.
 */
public record Palette(
    Color background,
    Color text,
    Color muted,
    Color gutter,
    Color gutterText,
    Color divider,
    Color addBg,
    Color addGutter,
    Color addAccent,
    Color removeBg,
    Color removeGutter,
    Color removeAccent,
    Color absentBg,
    Color gapBg,
    Color currentOutline,
    Color arrowIdle,
    Color arrowHot,
    /** Fundo da linha "igual para o diff, texto diferente" (ignorar espaços). */
    Color minorBg) {

  public static Palette forCurrentTheme() {
    Color panel = UIManager.getColor("Panel.background");
    boolean dark = panel != null && luminance(panel) < 0.5;
    return dark ? dark() : light();
  }

  public static Palette light() {
    return new Palette(
        new Color(0xFFFFFF),
        new Color(0x1F2328),
        new Color(0x8B949E),
        new Color(0xF3F4F6),
        new Color(0x9AA0A6),
        new Color(0xD8DBDF),
        new Color(0xE6F6EC),
        new Color(0xCFEBD9),
        new Color(0xA7E3BC),
        new Color(0xFDECEE),
        new Color(0xF8D7DA),
        new Color(0xF7B7BE),
        new Color(0xE9ECF0),
        new Color(0xEFF1F4),
        new Color(0x7D70E4),
        new Color(0xADB3BB),
        new Color(0x7D70E4),
        new Color(0xFFF6E0));
  }

  public static Palette dark() {
    return new Palette(
        new Color(0x1B1D21),
        new Color(0xE6E8EB),
        new Color(0x8B949E),
        new Color(0x23262B),
        new Color(0x767C85),
        new Color(0x33373D),
        new Color(0x14301F),
        new Color(0x1B3D28),
        new Color(0x2E6B45),
        new Color(0x3A1D22),
        new Color(0x4A242B),
        new Color(0x7E3B45),
        new Color(0x171A1D),
        new Color(0x24272C),
        new Color(0x9C93EE),
        new Color(0x5B626B),
        new Color(0x9C93EE),
        new Color(0x332C1A));
  }

  private static double luminance(Color c) {
    return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
  }
}
