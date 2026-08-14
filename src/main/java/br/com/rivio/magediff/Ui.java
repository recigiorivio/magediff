package br.com.rivio.magediff;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

/**
 * Botões e barra desenhados pelo próprio app.
 *
 * <p>O botão padrão do Swing muda de forma em cada plataforma e não conhece a
 * paleta do diff — no macOS escuro sai um bloco claro em cima de uma tela
 * escura. Aqui a mesma paleta que pinta as linhas pinta os controles, e o estado
 * (hover, pressionado, desabilitado) é explícito em vez de herdado do LAF.
 */
public final class Ui {

  private static final int RADIUS = 7;
  private static final int PAD_X = 11;
  private static final int PAD_Y = 6;

  private Ui() {
  }

  /** Estilo do botão: o primário é o que grava, e é o único preenchido — se
   * tudo fosse destacado, nada seria. */
  public enum Style {
    NORMAL, PRIMARY, GHOST
  }

  public static JButton button(String text, String tooltip, Style style, Palette palette,
      Runnable action) {
    FlatButton button = new FlatButton(text, null, style, palette);
    button.setToolTipText(tooltip);
    button.addActionListener((ActionEvent e) -> action.run());
    return button;
  }

  public static JToggleButton toggle(String text, String tooltip, Palette palette,
      boolean selected, Runnable action) {
    FlatToggle toggle = new FlatToggle(text, null, palette);
    toggle.setSelected(selected);
    toggle.setToolTipText(tooltip);
    toggle.addActionListener((ActionEvent e) -> action.run());
    return toggle;
  }

  /**
   * Botão só de ícone. A dica de contexto é OBRIGATÓRIA aqui — barra de ícones
   * sem tooltip transfere para o usuário o trabalho de adivinhar, e é o defeito
   * clássico desse estilo de barra.
   */
  public static JButton iconButton(Icons.Glyph glyph, String tooltip, Style style,
      Palette palette, Runnable action) {
    FlatButton button = new FlatButton(null, glyph, style, palette);
    button.setToolTipText(tooltip);
    button.addActionListener((ActionEvent e) -> action.run());
    return button;
  }

  public static JToggleButton iconToggle(Icons.Glyph glyph, String tooltip, Palette palette,
      boolean selected, Runnable action) {
    FlatToggle toggle = new FlatToggle(null, glyph, palette);
    toggle.setSelected(selected);
    toggle.setToolTipText(tooltip);
    toggle.addActionListener((ActionEvent e) -> action.run());
    return toggle;
  }

  /** Barra horizontal com fundo da paleta e uma linha embaixo. */
  public static JPanel bar(Palette palette) {
    JPanel panel = new JPanel() {
      @Override
      protected void paintComponent(Graphics g) {
        g.setColor(palette.gutter());
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(palette.divider());
        g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
      }
    };
    panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
    panel.setBorder(BorderFactory.createEmptyBorder(7, 8, 8, 8));
    panel.setOpaque(true);
    return panel;
  }

  /** Separador vertical fino entre grupos de botões. */
  public static JComponent separator(Palette palette) {
    JComponent sep = new JComponent() {
      @Override
      protected void paintComponent(Graphics g) {
        g.setColor(palette.divider());
        int x = getWidth() / 2;
        g.drawLine(x, 4, x, getHeight() - 4);
      }
    };
    Dimension size = new Dimension(13, 26);
    sep.setMinimumSize(size);
    sep.setPreferredSize(size);
    sep.setMaximumSize(size);
    return sep;
  }

  public static JComponent gap(int width) {
    JComponent spacer = new JComponent() {
    };
    Dimension size = new Dimension(width, 1);
    spacer.setMinimumSize(size);
    spacer.setPreferredSize(size);
    spacer.setMaximumSize(size);
    return spacer;
  }

  // ─── Pintura compartilhada ──────────────────────────────────────────────

  private static void paintButton(Graphics g, AbstractButton button, Style style,
      Palette palette, boolean active) {
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    boolean enabled = button.isEnabled();
    boolean pressed = button.getModel().isPressed();
    boolean hover = button.getModel().isRollover();

    Color fill = null;
    if (!enabled) {
      fill = style == Style.PRIMARY ? palette.absentBg() : null;
    } else if (style == Style.PRIMARY || active) {
      fill = pressed ? palette.currentOutline().darker() : palette.currentOutline();
    } else if (pressed) {
      fill = palette.gapBg().darker();
    } else if (hover) {
      fill = palette.gapBg();
    }

    if (fill != null) {
      g2.setColor(fill);
      g2.fillRoundRect(0, 0, button.getWidth() - 1, button.getHeight() - 1, RADIUS, RADIUS);
    }
    if (style != Style.GHOST && !(style == Style.PRIMARY && enabled) && !active) {
      g2.setColor(palette.divider());
      g2.drawRoundRect(0, 0, button.getWidth() - 1, button.getHeight() - 1, RADIUS, RADIUS);
    }
    g2.dispose();

    Color fg;
    if (!enabled) {
      fg = palette.muted();
    } else if (style == Style.PRIMARY || active) {
      fg = Color.WHITE;
    } else {
      fg = palette.text();
    }
    button.setForeground(fg);
  }

  private static void configure(AbstractButton button, Palette palette) {
    button.setContentAreaFilled(false);
    button.setBorderPainted(false);
    button.setFocusPainted(false);
    button.setOpaque(false);
    button.setRolloverEnabled(true);
    button.setMargin(new Insets(PAD_Y, PAD_X, PAD_Y, PAD_X));
    button.setBorder(BorderFactory.createEmptyBorder(PAD_Y, PAD_X, PAD_Y, PAD_X));
    button.setFont(button.getFont().deriveFont(Font.BOLD, 11.5f));
    button.setForeground(palette.text());
    // Sem isso o BoxLayout estica os botões até a altura da barra.
    button.setAlignmentY(JComponent.CENTER_ALIGNMENT);
  }

  /** Botão quadrado quando só tem ícone: alvo de clique previsível e alinhamento
   * igual entre todos, em vez de cada um com a largura do seu texto. */
  private static final int ICON_BUTTON = 30;
  private static final int ICON_SIZE = 16;

  private static void sizeForIcon(AbstractButton button) {
    Dimension size = new Dimension(ICON_BUTTON, ICON_BUTTON - 2);
    button.setMinimumSize(size);
    button.setPreferredSize(size);
    button.setMaximumSize(size);
  }

  private static void paintGlyph(Graphics g, AbstractButton button, Icons.Glyph glyph) {
    Icons.draw((Graphics2D) g, glyph, button.getWidth() / 2, button.getHeight() / 2,
        ICON_SIZE, button.getForeground());
  }

  private static final class FlatButton extends JButton {
    private final Style style;
    private final Palette palette;
    private final Icons.Glyph glyph;

    FlatButton(String text, Icons.Glyph glyph, Style style, Palette palette) {
      super(text);
      this.style = style;
      this.palette = palette;
      this.glyph = glyph;
      configure(this, palette);
      if (glyph != null) {
        sizeForIcon(this);
      }
    }

    @Override
    public Dimension getMaximumSize() {
      return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
      paintButton(g, this, style, palette, false);
      if (glyph == null) {
        super.paintComponent(g);
      } else {
        paintGlyph(g, this, glyph);
      }
    }
  }

  private static final class FlatToggle extends JToggleButton {
    private final Palette palette;
    private final Icons.Glyph glyph;

    FlatToggle(String text, Icons.Glyph glyph, Palette palette) {
      super(text);
      this.palette = palette;
      this.glyph = glyph;
      configure(this, palette);
      if (glyph != null) {
        sizeForIcon(this);
      }
    }

    @Override
    public Dimension getMaximumSize() {
      return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
      paintButton(g, this, Style.NORMAL, palette, isSelected());
      if (glyph == null) {
        super.paintComponent(g);
      } else {
        paintGlyph(g, this, glyph);
      }
    }
  }
}
