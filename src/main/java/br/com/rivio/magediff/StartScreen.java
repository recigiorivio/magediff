package br.com.rivio.magediff;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;

/**
 * A tela que abre primeiro: escolher entre comparar dois arquivos ou um
 * repositório Git.
 *
 * <p>Existe porque as duas coisas começam de perguntas diferentes — "quais dois
 * arquivos?" e "qual pasta, e contra o quê?". Enfiar as duas na mesma barra
 * obrigaria a tela a estar sempre metade desligada.
 */
public final class StartScreen extends JPanel {

  private static final int CARD_WIDTH = 268;
  private static final int CARD_HEIGHT = 190;

  public StartScreen(Palette palette, Runnable onFiles, Runnable onFolders, Runnable onGit) {
    setLayout(new GridBagLayout());
    setBackground(palette.background());

    JPanel column = new JPanel();
    column.setOpaque(false);
    column.setLayout(new BorderLayout(0, 22));

    column.add(header(palette), BorderLayout.NORTH);

    JPanel cards = new JPanel(new GridBagLayout());
    cards.setOpaque(false);
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new java.awt.Insets(0, 8, 0, 8);
    cards.add(new Card(palette, Icons.Glyph.TWO_FILES, "Comparar arquivos",
        "Dois arquivos lado a lado. Escolha, cole o caminho ou arraste para dentro.",
        onFiles), c);
    cards.add(new Card(palette, Icons.Glyph.OPEN_RIGHT, "Comparar pastas",
        "Duas pastas. Lista o que existe só de um lado e o que mudou nos dois.",
        onFolders), c);
    cards.add(new Card(palette, Icons.Glyph.BRANCH, "Comparar repositório Git",
        "Abra uma pasta e compare o que está local contra um commit ou outra branch.",
        onGit), c);
    column.add(cards, BorderLayout.CENTER);

    add(column, new GridBagConstraints());
  }

  private JComponent header(Palette palette) {
    JPanel box = new JPanel(new BorderLayout(14, 0));
    box.setOpaque(false);

    JLabel icon = new JLabel();
    BufferedImage logo = loadLogo();
    if (logo != null) {
      icon.setIcon(new javax.swing.ImageIcon(logo.getScaledInstance(64, 64, Image.SCALE_SMOOTH)));
    }
    box.add(icon, BorderLayout.WEST);

    JPanel texts = new JPanel();
    texts.setOpaque(false);
    texts.setLayout(new javax.swing.BoxLayout(texts, javax.swing.BoxLayout.Y_AXIS));
    JLabel title = new JLabel("MageDiff");
    title.setFont(title.getFont().deriveFont(Font.BOLD, 26f));
    title.setForeground(palette.text());
    JLabel subtitle = new JLabel("Comparar e juntar arquivos");
    subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 13f));
    subtitle.setForeground(palette.muted());
    title.setAlignmentX(LEFT_ALIGNMENT);
    subtitle.setAlignmentX(LEFT_ALIGNMENT);
    texts.add(title);
    texts.add(javax.swing.Box.createVerticalStrut(2));
    texts.add(subtitle);
    box.add(texts, BorderLayout.CENTER);
    return box;
  }

  private static BufferedImage loadLogo() {
    try (java.io.InputStream in = StartScreen.class.getResourceAsStream("/logo.png")) {
      return in == null ? null : ImageIO.read(in);
    } catch (Exception e) {
      return null;
    }
  }

  /** Cartão clicável: ícone, título e uma linha dizendo o que a opção faz. Sem a
   * linha de descrição, os dois títulos exigiriam adivinhação na primeira vez. */
  private static final class Card extends JPanel {

    private final Palette palette;
    private final Icons.Glyph glyph;
    private boolean hover;

    Card(Palette palette, Icons.Glyph glyph, String title, String description, Runnable action) {
      this.palette = palette;
      this.glyph = glyph;
      setPreferredSize(new Dimension(CARD_WIDTH, CARD_HEIGHT));
      setLayout(new BorderLayout(0, 6));
      setBorder(BorderFactory.createEmptyBorder(64, 20, 20, 20));
      setOpaque(false);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

      JLabel name = new JLabel(title);
      name.setFont(name.getFont().deriveFont(Font.BOLD, 15f));
      name.setForeground(palette.text());
      add(name, BorderLayout.NORTH);

      // JTextArea com quebra por palavra em vez de JLabel com HTML: o
      // `style='width:'` do renderizador HTML do Swing não é respeitado de forma
      // confiável, e o texto saía cortado no meio da palavra. Área de texto
      // quebra pela largura REAL do componente.
      JTextArea text = new JTextArea(description);
      text.setFont(text.getFont().deriveFont(Font.PLAIN, 12f));
      text.setForeground(palette.muted());
      text.setLineWrap(true);
      text.setWrapStyleWord(true);
      text.setEditable(false);
      text.setFocusable(false);
      text.setOpaque(false);
      text.setBorder(null);
      // Sem isto o clique cai no JTextArea e o cartão não recebe o evento.
      text.addMouseListener(new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent e) {
          action.run();
        }
      });
      add(text, BorderLayout.CENTER);

      addMouseListener(new MouseAdapter() {
        @Override
        public void mouseEntered(MouseEvent e) {
          hover = true;
          repaint();
        }

        @Override
        public void mouseExited(MouseEvent e) {
          hover = false;
          repaint();
        }

        @Override
        public void mouseClicked(MouseEvent e) {
          action.run();
        }
      });
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setColor(hover ? palette.gapBg() : palette.gutter());
      g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
      Color border = hover ? palette.currentOutline() : palette.divider();
      g2.setColor(border);
      g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
      Icons.draw(g2, glyph, 44, 40, 26, palette.currentOutline());
      g2.dispose();
      super.paintComponent(g);
    }
  }
}
