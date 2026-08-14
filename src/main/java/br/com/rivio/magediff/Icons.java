package br.com.rivio.magediff;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;

/**
 * Ícones desenhados como vetor.
 *
 * <p>Não são caracteres unicode (⟲, ⇄, ⌘…). Glifo de fonte varia de tamanho,
 * peso e alinhamento vertical entre plataformas — o mesmo "⟲" que fica certo no
 * macOS aparece minúsculo no Linux ou vira caixinha se a fonte não o tiver.
 * Desenhado, o ícone tem sempre a mesma proporção e recebe a cor do estado do
 * botão (normal, hover, desabilitado).
 *
 * <p>Todos são desenhados numa caixa nominal de 16×16 e escalados; a espessura
 * do traço acompanha a escala para não engordar em telas grandes.
 */
public final class Icons {

  /** Caixa de desenho de referência. */
  private static final double BOX = 16;

  public enum Glyph {
    OPEN_LEFT, OPEN_RIGHT,
    PREV, NEXT,
    COPY_RIGHT, COPY_LEFT,
    UNDO, REDO,
    CLIPBOARD_COPY, CLIPBOARD_PASTE,
    SWAP, RELOAD,
    SAVE_LEFT, SAVE_RIGHT, SAVE,
    COLLAPSE, WHITESPACE, BRANCH, TWO_FILES, HOME
  }

  private Icons() {
  }

  public static void draw(Graphics2D g, Glyph glyph, int cx, int cy, int size, Color color) {
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    double scale = size / BOX;
    g2.translate(cx - size / 2.0, cy - size / 2.0);
    g2.scale(scale, scale);
    g2.setColor(color);
    g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    paint(g2, glyph);
    g2.dispose();
  }

  private static void paint(Graphics2D g, Glyph glyph) {
    switch (glyph) {
      case OPEN_LEFT -> folder(g, true);
      case OPEN_RIGHT -> folder(g, false);
      case PREV -> chevron(g, true);
      case NEXT -> chevron(g, false);
      case COPY_RIGHT -> horizontalArrow(g, true);
      case COPY_LEFT -> horizontalArrow(g, false);
      case UNDO -> curvedArrow(g, true);
      case REDO -> curvedArrow(g, false);
      case CLIPBOARD_COPY -> copySheets(g);
      case CLIPBOARD_PASTE -> clipboard(g);
      case SWAP -> swap(g);
      case RELOAD -> reload(g);
      case SAVE_LEFT -> save(g, true);
      case SAVE_RIGHT -> save(g, false);
      case SAVE -> floppy(g);
      case COLLAPSE -> collapse(g);
      case WHITESPACE -> whitespace(g);
      case BRANCH -> branch(g);
      case TWO_FILES -> twoFiles(g);
      case HOME -> home(g);
    }
  }

  // ─── Glifos ─────────────────────────────────────────────────────────────

  /** Pasta com o lado indicado preenchido — é o que distingue "abrir esquerda"
   * de "abrir direita" sem precisar de texto. */
  private static void folder(Graphics2D g, boolean leftSide) {
    Path2D.Double p = new Path2D.Double();
    p.moveTo(1.5, 4);
    p.lineTo(6, 4);
    p.lineTo(7.2, 5.6);
    p.lineTo(14.5, 5.6);
    p.lineTo(14.5, 13);
    p.lineTo(1.5, 13);
    p.closePath();
    g.draw(p);
    if (leftSide) {
      g.fill(new java.awt.geom.Rectangle2D.Double(2, 7, 5, 5));
    } else {
      g.fill(new java.awt.geom.Rectangle2D.Double(9, 7, 5, 5));
    }
  }

  private static void chevron(Graphics2D g, boolean up) {
    Path2D.Double p = new Path2D.Double();
    if (up) {
      p.moveTo(4, 10);
      p.lineTo(8, 5.5);
      p.lineTo(12, 10);
    } else {
      p.moveTo(4, 6);
      p.lineTo(8, 10.5);
      p.lineTo(12, 6);
    }
    g.draw(p);
  }

  private static void horizontalArrow(Graphics2D g, boolean right) {
    double from = right ? 2.5 : 13.5;
    double to = right ? 13 : 3;
    g.draw(new java.awt.geom.Line2D.Double(from, 8, to, 8));
    Path2D.Double head = new Path2D.Double();
    double dir = right ? -1 : 1;
    head.moveTo(to + dir * 4, 4.5);
    head.lineTo(to, 8);
    head.lineTo(to + dir * 4, 11.5);
    g.draw(head);
  }

  /** Seta curva de desfazer/refazer: arco de 180° com a ponta na extremidade. */
  private static void curvedArrow(Graphics2D g, boolean undo) {
    g.draw(new Arc2D.Double(3, 4.5, 10, 8, undo ? 20 : 160, undo ? 160 : -160, Arc2D.OPEN));
    Path2D.Double head = new Path2D.Double();
    if (undo) {
      head.moveTo(3.2, 5.2);
      head.lineTo(3.0, 9.4);
      head.lineTo(6.9, 8.2);
    } else {
      head.moveTo(12.8, 5.2);
      head.lineTo(13.0, 9.4);
      head.lineTo(9.1, 8.2);
    }
    head.closePath();
    g.fill(head);
  }

  private static void copySheets(Graphics2D g) {
    g.draw(new java.awt.geom.RoundRectangle2D.Double(2.5, 2.5, 8, 9, 1.5, 1.5));
    g.draw(new java.awt.geom.RoundRectangle2D.Double(5.5, 5.5, 8, 9, 1.5, 1.5));
  }

  private static void clipboard(Graphics2D g) {
    g.draw(new java.awt.geom.RoundRectangle2D.Double(3.5, 3, 9, 11, 1.5, 1.5));
    g.draw(new java.awt.geom.RoundRectangle2D.Double(6, 1.6, 4, 2.8, 1, 1));
    g.draw(new java.awt.geom.Line2D.Double(5.8, 7.5, 10.2, 7.5));
    g.draw(new java.awt.geom.Line2D.Double(5.8, 10, 10.2, 10));
  }

  private static void swap(Graphics2D g) {
    g.draw(new java.awt.geom.Line2D.Double(3, 6, 13, 6));
    g.draw(new java.awt.geom.Line2D.Double(13, 10, 3, 10));
    Path2D.Double a = new Path2D.Double();
    a.moveTo(9.5, 3);
    a.lineTo(13, 6);
    a.lineTo(9.5, 9);
    g.draw(a);
    Path2D.Double b = new Path2D.Double();
    b.moveTo(6.5, 7);
    b.lineTo(3, 10);
    b.lineTo(6.5, 13);
    g.draw(b);
  }

  private static void reload(Graphics2D g) {
    g.draw(new Arc2D.Double(3, 3, 10, 10, 60, 280, Arc2D.OPEN));
    Path2D.Double head = new Path2D.Double();
    head.moveTo(11.2, 1.8);
    head.lineTo(12.6, 5.4);
    head.lineTo(8.8, 5.2);
    head.closePath();
    g.fill(head);
  }

  /** Disquete não diz nada para quem nasceu depois dele: seta para dentro de uma
   * base, com o lado indicado, é o gesto de "gravar deste lado". */
  private static void save(Graphics2D g, boolean leftSide) {
    g.draw(new java.awt.geom.Line2D.Double(8, 2.5, 8, 9.5));
    Path2D.Double head = new Path2D.Double();
    head.moveTo(5, 6.8);
    head.lineTo(8, 10);
    head.lineTo(11, 6.8);
    g.draw(head);
    g.draw(new java.awt.geom.Line2D.Double(3, 13, 13, 13));
    if (leftSide) {
      g.fill(new java.awt.geom.Rectangle2D.Double(3, 11.8, 4, 2.4));
    } else {
      g.fill(new java.awt.geom.Rectangle2D.Double(9, 11.8, 4, 2.4));
    }
  }

  /**
   * Disquete. É anacrônico — mas a alternativa que eu tinha desenhado (seta para
   * uma base) É o ícone de download em qualquer app moderno, e foi lida assim.
   * Entre o símbolo velho que todo mundo entende e o símbolo novo que significa
   * outra coisa, o velho ganha.
   */
  private static void floppy(Graphics2D g) {
    g.draw(new java.awt.geom.RoundRectangle2D.Double(2.5, 2.5, 11, 11, 1.5, 1.5));
    g.draw(new java.awt.geom.Rectangle2D.Double(5, 2.5, 6, 4));
    g.draw(new java.awt.geom.Rectangle2D.Double(4.5, 8.5, 7, 5));
  }

  /** Duas linhas cheias com um tracejado no meio: o contexto escondido. */
  private static void collapse(Graphics2D g) {
    g.draw(new java.awt.geom.Line2D.Double(2.5, 3.5, 13.5, 3.5));
    g.draw(new java.awt.geom.Line2D.Double(2.5, 12.5, 13.5, 12.5));
    for (double x = 3; x < 13; x += 3) {
      g.draw(new java.awt.geom.Line2D.Double(x, 8, x + 1.6, 8));
    }
  }

  /** Casinha: voltar à tela inicial. É o desenho que qualquer um lê como
   * "início" sem precisar de rótulo. */
  private static void home(Graphics2D g) {
    java.awt.geom.Path2D.Double roof = new java.awt.geom.Path2D.Double();
    roof.moveTo(1.8, 7.6);
    roof.lineTo(8, 2.2);
    roof.lineTo(14.2, 7.6);
    g.draw(roof);
    g.draw(new java.awt.geom.Rectangle2D.Double(3.6, 7.6, 8.8, 6.2));
    g.draw(new java.awt.geom.Rectangle2D.Double(6.6, 10, 2.8, 3.8));
  }

  /** Dois pontos numa linha e um terceiro derivando: o desenho de branch que
   * todo cliente de git usa. */
  private static void branch(Graphics2D g) {
    g.draw(new java.awt.geom.Line2D.Double(4.5, 3, 4.5, 13));
    g.fill(new java.awt.geom.Ellipse2D.Double(2.6, 1.4, 3.8, 3.8));
    g.fill(new java.awt.geom.Ellipse2D.Double(2.6, 11, 3.8, 3.8));
    g.fill(new java.awt.geom.Ellipse2D.Double(9.6, 1.4, 3.8, 3.8));
    // A curva que liga a branch de volta ao tronco.
    java.awt.geom.Path2D.Double merge = new java.awt.geom.Path2D.Double();
    merge.moveTo(11.5, 5.4);
    merge.curveTo(11.5, 9.5, 4.5, 7.5, 4.5, 11);
    g.draw(merge);
  }

  /** Duas folhas lado a lado: a comparação de dois arquivos. */
  private static void twoFiles(Graphics2D g) {
    g.draw(new java.awt.geom.RoundRectangle2D.Double(1.5, 2.5, 5.5, 11, 1.2, 1.2));
    g.draw(new java.awt.geom.RoundRectangle2D.Double(9, 2.5, 5.5, 11, 1.2, 1.2));
    g.draw(new java.awt.geom.Line2D.Double(2.8, 6, 5.7, 6));
    g.draw(new java.awt.geom.Line2D.Double(2.8, 9, 5.7, 9));
    g.draw(new java.awt.geom.Line2D.Double(10.3, 6, 13.2, 6));
    g.draw(new java.awt.geom.Line2D.Double(10.3, 9, 13.2, 9));
  }

  /** O "¶" do espaço: pontos entre traços, como editor mostra whitespace. */
  private static void whitespace(Graphics2D g) {
    g.draw(new java.awt.geom.Line2D.Double(2.5, 8, 5, 8));
    g.draw(new java.awt.geom.Line2D.Double(11, 8, 13.5, 8));
    g.fill(new java.awt.geom.Ellipse2D.Double(6, 7, 1.8, 1.8));
    g.fill(new java.awt.geom.Ellipse2D.Double(9, 7, 1.8, 1.8));
  }
}
