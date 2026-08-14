package br.com.rivio.magediff;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import br.com.rivio.magediff.DiffView.Side;

/**
 * Cabeçalho de cada coluna: o caminho do arquivo, os botões de abrir e gravar,
 * e a linha de metadados (data, tamanho, codificação, fim de linha).
 *
 * <p>Codificação e fim de linha são <b>editáveis</b>, não só informativos. É a
 * diferença entre um visualizador e uma ferramenta: quando dois arquivos "iguais"
 * aparecem diferentes, a causa costuma ser exatamente um desses dois — e resolver
 * exige poder trocá-los aqui, não reabrir o arquivo em outro programa.
 *
 * <p>Fica no cabeçalho do {@code JScrollPane} e não como uma faixa solta acima
 * dele: assim a largura é exatamente a da viewport, e cada coluna de campos
 * alinha com a coluna de texto que descreve.
 */
public final class PathBar extends JPanel {

  private static final int PATH_ROW = 30;
  private static final int INFO_ROW = 24;
  private static final int HEIGHT = PATH_ROW + INFO_ROW;
  private static final int BUTTON = 26;

  /** Codificações oferecidas. Curadas: a lista completa do JDK tem centenas e
   * transformaria a escolha num garimpo. */
  private static final Charset[] CHARSETS = {
      StandardCharsets.UTF_8,
      StandardCharsets.ISO_8859_1,
      Charset.forName("windows-1252"),
      StandardCharsets.UTF_16,
  };

  private static final String EOL_UNIX = "LF (Unix)";
  private static final String EOL_WINDOWS = "CRLF (Windows)";

  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("dd MMM yyyy 'às' HH:mm", new Locale("pt", "BR"))
          .withZone(ZoneId.systemDefault());

  /** O que a barra precisa avisar ao app. */
  public interface Listener {
    void browse(Side side);

    void pathTyped(Side side, String path);

    void save(Side side);

    void charsetChanged(Side side, Charset charset);

    void eolChanged(Side side, String eol);
  }

  private final DiffView view;
  private final Palette palette;
  private final Listener listener;

  private final SideWidgets leftSide;
  private final SideWidgets rightSide;
  /** Trava os eventos dos combos enquanto a tela é preenchida por código —
   * senão `setSelectedItem` dispara "o usuário trocou" e relê o arquivo sozinho. */
  private boolean updating;

  private final class SideWidgets {
    private final Side side;
    final JTextField field = new JTextField();
    final JLabel dirtyDot = new JLabel("●");
    final JButton browse;
    final JButton save;
    final JLabel info = new JLabel();
    final JComboBox<Charset> charset = new JComboBox<>(CHARSETS);
    final JComboBox<String> eol = new JComboBox<>(new String[] {EOL_UNIX, EOL_WINDOWS});

    SideWidgets(Side side, Icons.Glyph folder) {
      this.side = side;
      browse = Ui.iconButton(folder, "Escolher o arquivo", Ui.Style.NORMAL, palette,
          () -> listener.browse(side));
      save = Ui.iconButton(Icons.Glyph.SAVE, "Gravar este arquivo", Ui.Style.NORMAL, palette,
          () -> listener.save(side));
      configureField();
      configureDot();
      configureInfo();
      configureCombos();
    }

    private void configureField() {
      field.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
      field.setBackground(palette.background());
      field.setForeground(palette.text());
      field.setCaretColor(palette.text());
      resetBorder();
      // O JTextField tem drop target próprio (para texto). Mantido, ele engoliria
      // um arquivo solto em cima do caminho e inseriria o path como TEXTO em vez
      // de abrir o arquivo. Sem ele, o arrasto sobe para o DropTarget da barra.
      field.setDropTarget(null);
      field.setDragEnabled(false);
      field.addActionListener((ActionEvent e) -> {
        String typed = field.getText().trim();
        // Caminho com erro de digitação não pode trocar (nem esvaziar) o lado
        // que já está aberto: só avisa o app quando o arquivo existe.
        if (!typed.isEmpty() && Files.isRegularFile(Path.of(typed))) {
          listener.pathTyped(side, typed);
        } else {
          field.setBorder(BorderFactory.createCompoundBorder(
              BorderFactory.createLineBorder(new Color(0xC0392B)),
              BorderFactory.createEmptyBorder(2, 6, 2, 6)));
        }
      });
    }

    void resetBorder() {
      field.setBorder(BorderFactory.createCompoundBorder(
          BorderFactory.createLineBorder(palette.divider()),
          BorderFactory.createEmptyBorder(2, 6, 2, 6)));
    }

    private void configureDot() {
      dirtyDot.setForeground(palette.currentOutline());
      dirtyDot.setFont(dirtyDot.getFont().deriveFont(Font.BOLD, 13f));
      dirtyDot.setToolTipText("Há alteração não gravada deste lado");
      dirtyDot.setVisible(false);
    }

    private void configureInfo() {
      info.setFont(info.getFont().deriveFont(Font.PLAIN, 10.5f));
      info.setForeground(palette.muted());
    }

    private void configureCombos() {
      for (JComboBox<?> combo : new JComboBox<?>[] {charset, eol}) {
        combo.setFont(combo.getFont().deriveFont(Font.PLAIN, 10.5f));
        combo.setFocusable(false);
      }
      charset.setToolTipText("Codificação: trocar relê o arquivo do disco");
      eol.setToolTipText("Fim de linha usado ao gravar");
      charset.addActionListener(e -> {
        if (!updating) {
          listener.charsetChanged(side, (Charset) charset.getSelectedItem());
        }
      });
      eol.addActionListener(e -> {
        if (!updating) {
          listener.eolChanged(side, EOL_WINDOWS.equals(eol.getSelectedItem()) ? "\r\n" : "\n");
        }
      });
    }

    void addTo(JPanel parent) {
      parent.add(field);
      parent.add(dirtyDot);
      parent.add(browse);
      parent.add(save);
      parent.add(info);
      parent.add(charset);
      parent.add(eol);
    }

    void update(TextFile file) {
      field.setText(file.loaded() ? file.path().toString() : "");
      field.setToolTipText(field.getText());
      dirtyDot.setVisible(file.dirty());
      save.setEnabled(file.dirty());
      resetBorder();
      info.setText(describe(file));
      charset.setSelectedItem(file.charset());
      eol.setSelectedItem("CRLF".equals(file.eolLabel()) ? EOL_WINDOWS : EOL_UNIX);
    }

    /** Posiciona as duas fileiras deste lado dentro de [x, x+width). */
    void layoutAt(int x, int width) {
      int pad = 4;
      int dot = 12;
      int y = (PATH_ROW - BUTTON) / 2;
      int fieldW = Math.max(40, width - BUTTON * 2 - dot - pad * 5);
      field.setBounds(x + pad, y + 1, fieldW, BUTTON - 2);
      dirtyDot.setBounds(x + pad + fieldW + 2, y, dot, BUTTON);
      browse.setBounds(x + pad + fieldW + dot + 4, y, BUTTON, BUTTON);
      save.setBounds(x + pad + fieldW + dot + BUTTON + 6, y, BUTTON, BUTTON);

      int iy = PATH_ROW + 1;
      int comboH = INFO_ROW - 4;
      int eolW = 108;
      int charsetW = 116;
      eol.setBounds(x + width - eolW - pad, iy, eolW, comboH);
      charset.setBounds(x + width - eolW - charsetW - pad * 2, iy, charsetW, comboH);
      info.setBounds(x + pad + 2, iy, Math.max(20, width - eolW - charsetW - pad * 5), comboH);
    }
  }

  public PathBar(DiffView view, Palette palette, Listener listener) {
    this.view = view;
    this.palette = palette;
    this.listener = listener;
    setLayout(null);
    setPreferredSize(new Dimension(10, HEIGHT));
    leftSide = new SideWidgets(Side.LEFT, Icons.Glyph.OPEN_LEFT);
    rightSide = new SideWidgets(Side.RIGHT, Icons.Glyph.OPEN_RIGHT);
    leftSide.addTo(this);
    rightSide.addTo(this);
  }

  public void update(TextFile left, TextFile right) {
    updating = true;
    leftSide.update(left);
    rightSide.update(right);
    updating = false;
    doLayout();
    repaint();
  }

  /** Data e tamanho, no formato que o Beyond Compare usa — é a informação que
   * responde "estou olhando a versão certa?" antes de olhar o conteúdo. */
  private static String describe(TextFile file) {
    if (!file.loaded()) {
      return "(sem arquivo)";
    }
    String size = String.format(new Locale("pt", "BR"), "%,d bytes", file.byteSize());
    String when = file.modifiedAt() == null ? "" : STAMP.format(file.modifiedAt()) + "   ·   ";
    return when + size;
  }

  /**
   * Posiciona pela MESMA geometria que o diff usa ({@code xArrow},
   * {@code xGutterRight}) — é o que faz cada campo terminar exatamente onde a
   * coluna dele termina, inclusive quando a calha de números cresce.
   */
  @Override
  public void doLayout() {
    leftSide.layoutAt(0, view.xArrow());
    rightSide.layoutAt(view.xGutterRight(), getWidth() - view.xGutterRight());
  }

  @Override
  protected void paintComponent(Graphics g) {
    g.setColor(palette.gutter());
    g.fillRect(0, 0, getWidth(), getHeight());
    g.setColor(palette.divider());
    g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
    g.drawLine(0, PATH_ROW, getWidth(), PATH_ROW);
    // Divisória entre as colunas, para o olho ligar cabeçalho → coluna.
    g.drawLine(view.xArrow(), 2, view.xArrow(), getHeight() - 3);
  }

  /** Usado pelo instalador de arrasto: qual lado corresponde a este x. */
  public boolean isLeftAt(int x) {
    return x < view.xArrow();
  }

  @Override
  public Dimension getPreferredSize() {
    return new Dimension(super.getPreferredSize().width, HEIGHT);
  }
}
