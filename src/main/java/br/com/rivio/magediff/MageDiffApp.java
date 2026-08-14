package br.com.rivio.magediff;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import br.com.rivio.magediff.DiffEngine.Hunk;
import br.com.rivio.magediff.DiffEngine.Result;

/** Comparador e merge de dois arquivos, no espírito do Beyond Compare. */
public final class MageDiffApp extends JFrame {

  // Não são finais: abrir outro arquivo troca a instância inteira, em vez de
  // mutar eol/BOM/quebra-final de um TextFile existente — esses três descrevem o
  // arquivo que veio do disco e não fazem sentido reatribuir.
  private TextFile left;
  private TextFile right;
  private final DiffView view = new DiffView();
  private final JScrollPane scroll = new JScrollPane(view);
  private final JScrollBar hBar = new JScrollBar(JScrollBar.HORIZONTAL);
  private final JLabel status = new JLabel();
  private final JLabel warning = new JLabel();
  private final UndoStack history = new UndoStack();
  private final PathBar pathBar;
  private Result result;

  // Criados em buildToolbar(): precisam da paleta, que vem do DiffView.
  private JToggleButton collapseToggle;
  private JMenuItem saveLeftItem;
  private JMenuItem saveRightItem;
  private JMenuItem undoItem;
  private JMenuItem redoItem;
  private JButton undoButton;
  private JButton redoButton;
  private JButton copyButton;
  private JButton pasteButton;
  private JToggleButton ignoreWsToggle;
  private Minimap minimap;
  /** Rastro da última cópia, em linhas da ESQUERDA (semiaberto). -1 = nenhum. */
  private int trailFrom = -1;
  private int trailTo = -1;

  // ─── Modo Git ───
  private final java.awt.CardLayout cards = new java.awt.CardLayout();
  private final JPanel root = new JPanel(cards);
  { root.setOpaque(true); }
  private GitHeader gitHeader;
  private ChangedFilesList filesList;
  private GitRepo repo;
  private String openPath;

  private MageDiffApp(TextFile left, TextFile right) {
    super("MageDiff");
    this.left = left;
    this.right = right;
    this.pathBar = new PathBar(view, view.palette(), new PathBar.Listener() {
      @Override
      public void browse(DiffView.Side side) {
        open(side == DiffView.Side.LEFT);
      }

      @Override
      public void pathTyped(DiffView.Side side, String path) {
        loadPath(side, path);
      }

      @Override
      public void save(DiffView.Side side) {
        MageDiffApp.this.save(side == DiffView.Side.LEFT);
      }

      @Override
      public void charsetChanged(DiffView.Side side, java.nio.charset.Charset charset) {
        reopenWithCharset(side, charset);
      }

      @Override
      public void eolChanged(DiffView.Side side, String eol) {
        (side == DiffView.Side.LEFT ? left : right).setEol(eol);
        if (repo == null) {
      pathBar.update(left, right);
    } else {
      gitHeader.repaint();
    }
        refreshStatus();
      }
    });
    view.setMergeListener(this::applyHunk);
    view.setUndoListener(this::undo);
    // Soltar arquivo no diff OU na barra de caminho: os dois são "o lado".
    FileDrop.install(view, point -> point.x < view.xArrow(), this::dropFile);
    FileDrop.install(pathBar, point -> point.x < view.xArrow(), this::dropFile);
    view.setSelectionListener(this::refreshStatus);

    scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
    scroll.setColumnHeaderView(pathBar);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.getViewport().setBackground(view.palette().background());

    hBar.addAdjustmentListener(e -> view.setHOffset(e.getValue()));
    // Redimensionar muda a largura das colunas, logo muda quanto há para rolar
    // na horizontal. Sem isto a barra fica com o alcance da largura anterior.
    scroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
      @Override
      public void componentResized(java.awt.event.ComponentEvent e) {
        syncScrollBar();
        pathBar.doLayout();
        pathBar.repaint();
      }
    });

    minimap = new Minimap(view, scroll);
    JPanel center = new JPanel(new BorderLayout());
    center.add(scroll, BorderLayout.CENTER);
    center.add(minimap, BorderLayout.EAST);

    gitHeader = new GitHeader(view, view.palette(), new GitHeader.Listener() {
      @Override
      public void sidesChanged(GitRepo.Side from, GitRepo.Side to) {
        refreshGitChanges();
      }

      @Override
      public void chooseFolder() {
        openGitFolder();
      }
    });
    filesList = new ChangedFilesList(view.palette(), this::openGitFile);

    JPanel diffCard = new JPanel(new BorderLayout());
    diffCard.add(buildToolbar(), BorderLayout.NORTH);
    diffCard.add(center, BorderLayout.CENTER);
    diffCard.add(buildBottom(), BorderLayout.SOUTH);
    diffCard.add(filesList, BorderLayout.WEST);
    filesList.setVisible(false);

    root.setBackground(view.palette().background());
    root.add(new StartScreen(view.palette(), this::startFileMode, this::openGitFolder), "start");
    root.add(diffCard, "diff");

    setJMenuBar(buildMenuBar());
    setLayout(new BorderLayout());
    add(root, BorderLayout.CENTER);
    installShortcuts();

    applyIcon();
    setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    setSize(1280, 780);
    setLocationRelativeTo(null);
    // Com arquivos na linha de comando (ou arrastados no ícone), a tela inicial
    // só atrasaria; sem eles, ela é a pergunta que falta responder.
    if (left.loaded() || right.loaded()) {
      startFileMode();
    } else {
      recompute();
      showStart();
    }
  }

  /**
   * Ícone da janela e do dock, lido do jar. Falha em silêncio de propósito: app
   * sem ícone é feio, app que não abre por causa do ícone é defeito.
   *
   * <p>No macOS o ícone do dock vem do bundle (.icns) quando empacotado; o
   * {@code Taskbar} cobre o caso de rodar direto pelo jar/classes.
   */
  private void applyIcon() {
    try (java.io.InputStream in = getClass().getResourceAsStream("/logo.png")) {
      if (in == null) {
        return;
      }
      java.awt.image.BufferedImage icon = javax.imageio.ImageIO.read(in);
      if (icon == null) {
        return;
      }
      setIconImage(icon);
      if (java.awt.Taskbar.isTaskbarSupported()) {
        java.awt.Taskbar.getTaskbar().setIconImage(icon);
      }
    } catch (Exception ignored) {
      // sem ícone, e segue
    }
  }

  // ─── Construção da UI ───────────────────────────────────────────────────

  /**
   * Duas fileiras: arquivo em cima, navegação/edição embaixo.
   *
   * <p>Numa fileira só, com 12 botões, "Salvar" saía da janela em 1320px — e
   * botão que só aparece se a janela for grande o bastante não é botão.
   */
  /**
   * Uma fileira só. Chegou a ter duas, mas depois que abrir foi para a barra de
   * caminho e gravar para o menu, a de cima ficou com dois ícones perdidos num
   * espaço vazio — fileira quase vazia parece defeito, não organização.
   */
  private JComponent buildToolbar() {
    return buildEditRow(view.palette());
  }

  private JPanel buildEditRow(Palette p) {
    JPanel bar = Ui.bar(p);
    bar.add(Ui.iconButton(Icons.Glyph.PREV, "Diferença anterior (F7 ou " + shortcutName() + "↑)",
        Ui.Style.NORMAL, p, view::previousHunk));
    bar.add(Ui.gap(4));
    bar.add(Ui.iconButton(Icons.Glyph.NEXT, "Próxima diferença (F8 ou " + shortcutName() + "↓)",
        Ui.Style.NORMAL, p, view::nextHunk));
    bar.add(Ui.separator(p));

    bar.add(Ui.iconButton(Icons.Glyph.COPY_LEFT,
        "Copiar o bloco para a esquerda (⌥←)", Ui.Style.NORMAL, p, () -> applyCurrent(false)));
    bar.add(Ui.gap(4));
    bar.add(Ui.iconButton(Icons.Glyph.COPY_RIGHT,
        "Copiar o bloco para a direita (⌥→)", Ui.Style.NORMAL, p, () -> applyCurrent(true)));
    bar.add(Ui.separator(p));

    undoButton = Ui.iconButton(Icons.Glyph.UNDO, "Desfazer (" + shortcutName() + "Z)",
        Ui.Style.NORMAL, p, this::undo);
    redoButton = Ui.iconButton(Icons.Glyph.REDO, "Refazer (" + shortcutName() + "⇧Z)",
        Ui.Style.NORMAL, p, this::redo);
    copyButton = Ui.iconButton(Icons.Glyph.CLIPBOARD_COPY,
        "Copiar as linhas selecionadas (" + shortcutName() + "C)",
        Ui.Style.NORMAL, p, this::copySelection);
    pasteButton = Ui.iconButton(Icons.Glyph.CLIPBOARD_PASTE,
        "Colar sobre a seleção (" + shortcutName() + "V)",
        Ui.Style.NORMAL, p, this::pasteIntoSelection);
    bar.add(undoButton);
    bar.add(Ui.gap(4));
    bar.add(redoButton);
    bar.add(Ui.separator(p));
    bar.add(copyButton);
    bar.add(Ui.gap(4));
    bar.add(pasteButton);
    bar.add(Box.createHorizontalGlue());

    bar.add(Ui.iconButton(Icons.Glyph.SWAP, "Trocar os lados de posição",
        Ui.Style.NORMAL, p, this::swapSides));
    bar.add(Ui.gap(4));
    bar.add(Ui.iconButton(Icons.Glyph.RELOAD, "Reler os dois arquivos do disco (F5)",
        Ui.Style.NORMAL, p, this::reload));
    bar.add(Ui.separator(p));

    collapseToggle = Ui.iconToggle(Icons.Glyph.COLLAPSE,
        "Só mudanças: esconder blocos longos de linhas iguais", p, true, this::recompute);
    bar.add(collapseToggle);
    bar.add(Ui.gap(4));
    ignoreWsToggle = Ui.iconToggle(Icons.Glyph.WHITESPACE,
        "Ignorar espaços: não tratar diferença só de indentação como diferença",
        p, false, this::recompute);
    bar.add(ignoreWsToggle);
    return bar;
  }

  /**
   * Barra de menus. Com a barra de ferramentas só de ícones, o menu é o que dá
   * nome às ações e mostra os atalhos — sem ele, descobrir o que o app faz
   * dependeria de passar o mouse em cada ícone.
   */
  private JMenuBar buildMenuBar() {
    int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    JMenuBar bar = new JMenuBar();

    JMenu file = new JMenu("Arquivo");
    file.add(item("Tela inicial", KeyStroke.getKeyStroke(KeyEvent.VK_0, menu), this::showStart));
    file.add(item("Comparar repositório Git…",
        KeyStroke.getKeyStroke(KeyEvent.VK_G, menu), this::openGitFolder));
    file.add(item("Nova comparação", KeyStroke.getKeyStroke(KeyEvent.VK_N, menu),
        this::newComparison));
    file.addSeparator();
    file.add(item("Abrir esquerda…", KeyStroke.getKeyStroke(KeyEvent.VK_O, menu),
        () -> open(true)));
    file.add(item("Abrir direita…",
        KeyStroke.getKeyStroke(KeyEvent.VK_O, menu | KeyEvent.SHIFT_DOWN_MASK),
        () -> open(false)));
    file.add(item("Recarregar do disco", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0),
        this::reload));
    file.addSeparator();
    saveLeftItem = item("Salvar esquerda", KeyStroke.getKeyStroke(KeyEvent.VK_S, menu),
        () -> save(true));
    saveRightItem = item("Salvar direita",
        KeyStroke.getKeyStroke(KeyEvent.VK_S, menu | KeyEvent.SHIFT_DOWN_MASK),
        () -> save(false));
    file.add(saveLeftItem);
    file.add(saveRightItem);
    file.addSeparator();
    file.add(item("Fechar", KeyStroke.getKeyStroke(KeyEvent.VK_W, menu), this::closeWindow));
    bar.add(file);

    JMenu edit = new JMenu("Editar");
    undoItem = item("Desfazer", KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu), this::undo);
    redoItem = item("Refazer",
        KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu | KeyEvent.SHIFT_DOWN_MASK), this::redo);
    edit.add(undoItem);
    edit.add(redoItem);
    edit.addSeparator();
    edit.add(item("Copiar linhas", KeyStroke.getKeyStroke(KeyEvent.VK_C, menu),
        this::copySelection));
    edit.add(item("Colar sobre a seleção", KeyStroke.getKeyStroke(KeyEvent.VK_V, menu),
        this::pasteIntoSelection));
    edit.addSeparator();
    edit.add(item("Copiar bloco para a direita",
        KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, KeyEvent.ALT_DOWN_MASK),
        () -> applyCurrent(true)));
    edit.add(item("Copiar bloco para a esquerda",
        KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, KeyEvent.ALT_DOWN_MASK),
        () -> applyCurrent(false)));
    bar.add(edit);

    JMenu viewMenu = new JMenu("Ver");
    viewMenu.add(item("Próxima diferença", KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0),
        view::nextHunk));
    viewMenu.add(item("Diferença anterior", KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0),
        view::previousHunk));
    viewMenu.addSeparator();
    viewMenu.add(item("Só mudanças", null, () -> {
      collapseToggle.setSelected(!collapseToggle.isSelected());
      recompute();
    }));
    viewMenu.add(item("Ignorar espaços", null, () -> {
      ignoreWsToggle.setSelected(!ignoreWsToggle.isSelected());
      recompute();
    }));
    viewMenu.addSeparator();
    viewMenu.add(item("Trocar lados", null, this::swapSides));
    bar.add(viewMenu);
    return bar;
  }

  private JMenuItem item(String label, KeyStroke stroke, Runnable action) {
    JMenuItem menuItem = new JMenuItem(label);
    if (stroke != null) {
      menuItem.setAccelerator(stroke);
    }
    menuItem.addActionListener(e -> action.run());
    return menuItem;
  }

  /** Começa do zero: dois lados vazios. Descarta o que não foi gravado, então
   * pergunta antes — mesma regra do Recarregar. */
  private void newComparison() {
    if (!confirmDiscard("Nova comparação vai descartar as alterações não gravadas.")) {
      return;
    }
    left = TextFile.empty();
    right = TextFile.empty();
    history.clear();
    view.clearSelection();
    clearTrail();
    recompute();
    flash("Nova comparação — use Arquivo › Abrir");
  }

  private void closeWindow() {
    if (confirmDiscard("Há alterações não gravadas. Fechar de qualquer jeito?")) {
      dispose();
    }
  }

  /** {@code true} quando pode seguir: não há nada pendente ou o usuário confirmou. */
  private boolean confirmDiscard(String message) {
    if (!(left.dirty() || right.dirty())) {
      return true;
    }
    return JOptionPane.showConfirmDialog(this, message, "Confirmar",
        JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
  }

  /** "⌘" no macOS, "Ctrl+" no resto — só para o texto das dicas. */
  private static String shortcutName() {
    return System.getProperty("os.name", "").toLowerCase().contains("mac") ? "⌘" : "Ctrl+";
  }

  private JComponent buildBottom() {
    JPanel panel = new JPanel(new BorderLayout());
    panel.add(hBar, BorderLayout.NORTH);
    JPanel bars = new JPanel(new BorderLayout());
    bars.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));
    status.setFont(status.getFont().deriveFont(Font.PLAIN, 11f));
    warning.setFont(warning.getFont().deriveFont(Font.PLAIN, 11f));
    warning.setForeground(new Color(0xB4690E));
    bars.add(status, BorderLayout.WEST);
    bars.add(warning, BorderLayout.EAST);
    panel.add(bars, BorderLayout.SOUTH);
    return panel;
  }

  private static Action action(String name, java.util.function.Consumer<ActionEvent> handler) {
    return new AbstractAction(name) {
      @Override
      public void actionPerformed(ActionEvent e) {
        handler.accept(e);
      }
    };
  }

  /**
   * Atalhos que o MENU não cobre.
   *
   * <p>Cada tecla tem um dono só. Os aceleradores dos itens de menu já registram
   * ⌘Z, ⌘C, ⌘V, ⌥→, ⌥←, F5, F7 e F8 — repetir isso aqui deixava nove teclas em
   * dois mapas ao mesmo tempo, e se o mesmo ⌥→ disparasse duas vezes o app
   * mesclaria DOIS blocos num toque. Aqui ficam só as alternativas que não têm
   * item de menu correspondente.
   *
   * <p>⌘↑/⌘↓ existem porque o macOS captura F7/F8 para as teclas de mídia quando
   * "usar F1, F2 como teclas de função" está desligado — que é o padrão.
   */
  private void installShortcuts() {
    int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    bind("prev", e -> view.previousHunk(), KeyStroke.getKeyStroke(KeyEvent.VK_UP, menu));
    bind("next", e -> view.nextHunk(), KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, menu));
    bind("redoAlt", e -> redo(), KeyStroke.getKeyStroke(KeyEvent.VK_Y, menu));
    bind("escape", e -> view.clearSelection(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
  }

  private void bind(String id, java.util.function.Consumer<ActionEvent> handler,
      KeyStroke... strokes) {
    for (KeyStroke stroke : strokes) {
      getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, id);
    }
    getRootPane().getActionMap().put(id, action(id, handler));
  }

  // ─── Diff e merge ───────────────────────────────────────────────────────

  private void recompute() {
    result = DiffEngine.diff(left.lines(), right.lines(),
        collapseToggle.isSelected(), ignoreWsToggle.isSelected(), trailFrom, trailTo);
    view.setData(result, left.lines(), right.lines());
    minimap.repaint();
    syncScrollBar();
    refreshStatus();
    pathBar.update(left, right);
    setTitle(String.format("MageDiff — %s%s × %s%s",
        left.name(), left.dirty() ? " *" : "",
        right.name(), right.dirty() ? " *" : ""));
  }

  private void syncScrollBar() {
    SwingUtilities.invokeLater(() -> {
      int visible = view.textWidth();
      hBar.setValues(view.hOffset(), visible, 0, Math.max(visible, view.contentWidth()));
      hBar.setBlockIncrement(Math.max(16, visible / 2));
      hBar.setUnitIncrement(24);
      hBar.setEnabled(view.contentWidth() > visible);
    });
  }

  private void applyCurrent(boolean toRight) {
    if (view.currentHunk() >= 0) {
      applyHunk(view.currentHunk(), toRight);
    }
  }

  /**
   * "Copiar para a direita" = fazer o lado direito ficar igual ao esquerdo NESTE
   * bloco. Como o hunk guarda intervalos semiabertos, a mesma operação cobre
   * substituir, inserir (intervalo de destino vazio) e apagar (intervalo de
   * origem vazio) — não há três caminhos, há um.
   *
   * <p>Depois de aplicar, o diff é recalculado do zero. Tentar remendar as rows
   * e reindexar os hunks à mão é a fonte clássica de merge que corrompe arquivo;
   * recalcular é O(n·m) num arquivo que já está em memória, e é sempre correto.
   */
  private void applyHunk(int hunkIndex, boolean toRight) {
    if (result == null || hunkIndex < 0 || hunkIndex >= result.hunks().size()) {
      return;
    }
    Hunk hunk = result.hunks().get(hunkIndex);
    // O lado "revisão" do modo Git não tem arquivo em disco: copiar para ele
    // mudaria só a memória e sumiria no próximo clique, dando a impressão de
    // que o merge foi aplicado.
    TextFile destination = toRight ? right : left;
    if (destination.readOnly()) {
      flash("Este lado é um commit — não dá para alterar. Copie para o outro lado.");
      return;
    }
    history.record(toRight ? "copiar para a direita" : "copiar para a esquerda", left, right);
    if (toRight) {
      Merge.toRight(hunk, left.lines(), right.lines());
      right.markDirty();
    } else {
      Merge.toLeft(hunk, left.lines(), right.lines());
      left.markDirty();
    }
    // O rastro é declarado ANTES do recompute porque o próprio recompute precisa
    // dele para não colapsar o trecho. Em linhas da esquerda: depois da cópia os
    // dois lados são iguais ali, então o tamanho é o do lado que mandou.
    int copied = toRight ? hunk.oldCount() : hunk.newCount();
    trailFrom = hunk.oldFrom();
    trailTo = hunk.oldFrom() + copied;
    recompute();
    // Uma cópia que APAGA linhas não deixa faixa (não sobrou nada onde marcar);
    // nesse caso o ↶ ancora na linha seguinte, que é o ponto de volta possível.
    int fromRow = view.rowOfLine(DiffView.Side.LEFT, trailFrom);
    int toRow = copied == 0 ? fromRow : view.rowOfLine(DiffView.Side.LEFT, trailTo - 1);
    view.setMergeMark(fromRow, Math.max(fromRow, toRow));
    // O bloco aplicado deixou de existir; ficar no mesmo índice cai naturalmente
    // no próximo, que é o que se quer ao resolver um arquivo de cima a baixo.
    if (view.hunkCount() > 0) {
      view.goToHunk(Math.min(hunkIndex, view.hunkCount() - 1));
    }
  }

  // ─── Área de transferência e desfazer ───────────────────────────────────

  private void copySelection() {
    if (!view.hasSelection()) {
      return;
    }
    DiffView.Side side = view.selectedSide();
    int[] range = view.selectionLineRange(side);
    List<String> lines = side == DiffView.Side.LEFT ? left.lines() : right.lines();
    if (range[0] >= range[1]) {
      return;
    }
    String text = String.join("\n", lines.subList(range[0], range[1]));
    Toolkit.getDefaultToolkit().getSystemClipboard()
        .setContents(new StringSelection(text), null);
    flash(String.format("%d linha(s) copiada(s)", range[1] - range[0]));
  }

  /**
   * Cola substituindo a seleção. Com seleção vazia daquele lado (só existe
   * bloco do outro), o intervalo é vazio e a colagem vira inserção no ponto —
   * mesmo intervalo semiaberto dos blocos, mesma operação de {@link Merge}.
   */
  private void pasteIntoSelection() {
    if (!view.hasSelection()) {
      flash("Selecione onde colar (clique numa linha)");
      return;
    }
    String clipboard = readClipboard();
    if (clipboard == null) {
      flash("A área de transferência não tem texto");
      return;
    }
    DiffView.Side side = view.selectedSide();
    TextFile target = side == DiffView.Side.LEFT ? left : right;
    int[] range = view.selectionLineRange(side);
    List<String> incoming = splitLines(clipboard);

    history.record("colar", left, right);
    clearTrail();
    Merge.replaceRange(target.lines(), range[0], range[1], incoming);
    target.markDirty();
    recompute();
    flash(String.format("%d linha(s) coladas em %s", incoming.size(), target.name()));
  }

  private static String readClipboard() {
    try {
      Object data = Toolkit.getDefaultToolkit().getSystemClipboard()
          .getData(DataFlavor.stringFlavor);
      return data == null ? null : data.toString();
    } catch (UnsupportedFlavorException | IOException | IllegalStateException e) {
      // Clipboard ocupado por outro app ou com conteúdo não-texto: não é erro
      // que mereça diálogo, é caso de avisar na barra e seguir.
      return null;
    }
  }

  /** Aceita CRLF, LF e CR — o texto pode vir de qualquer app. A quebra final não
   * vira linha vazia extra, pela mesma razão do {@code TextFile}. */
  private static List<String> splitLines(String text) {
    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
    if (normalized.isEmpty()) {
      return List.of();
    }
    List<String> out = new ArrayList<>(Arrays.asList(normalized.split("\n", -1)));
    if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
      out.remove(out.size() - 1);
    }
    return out;
  }

  /** Some com o rastro: ele descreve UMA cópia, e qualquer outra ação torna a
   * marca mentirosa. */
  private void clearTrail() {
    trailFrom = -1;
    trailTo = -1;
    view.clearMergeMark();
  }

  private void undo() {
    if (history.undo(left, right)) {
      clearTrail();
      recompute();
      flash("Desfeito");
    }
  }

  private void redo() {
    if (history.redo(left, right)) {
      clearTrail();
      recompute();
      flash("Refeito");
    }
  }

  /** Mensagem passageira na barra de status; some no próximo recálculo. */
  private void flash(String message) {
    warning.setText(message);
  }

  private void refreshStatus() {
    if (result == null) {
      return;
    }
    String position;
    if (view.hunkCount() == 0) {
      position = "nenhuma diferença";
    } else if (view.currentHunk() < 0) {
      // Antes dizia "bloco 0 de 50": índice interno vazando para a tela.
      position = view.hunkCount() + " diferença(s)";
    } else {
      position = String.format("bloco %d de %d", view.currentHunk() + 1, view.hunkCount());
    }
    // O contador de "sem importância" só aparece quando existe: linha morta na
    // barra em toda comparação normal treina o olho a não ler a barra.
    String minor = result.minor() == 0 ? ""
        : String.format("   ·   %d só de espaço", result.minor());
    status.setText(String.format("+%d  −%d%s   ·   %s   ·   %s: %s%s   ·   %s: %s%s",
        result.added(), result.removed(), minor, position,
        left.name(), left.eolLabel(), left.bom() ? " + BOM" : "",
        right.name(), right.eolLabel(), right.bom() ? " + BOM" : ""));
    warning.setText(buildWarning());
    saveLeftItem.setEnabled(left.dirty());
    saveRightItem.setEnabled(right.dirty());
    undoItem.setEnabled(history.canUndo());
    redoItem.setEnabled(history.canRedo());
    undoButton.setEnabled(history.canUndo());
    redoButton.setEnabled(history.canRedo());
    copyButton.setEnabled(view.hasSelection());
    pasteButton.setEnabled(view.hasSelection());
    undoButton.setToolTipText(history.canUndo()
        ? "Desfazer: " + history.undoLabel() + " (" + shortcutName() + "Z)"
        : "Nada a desfazer");
    redoButton.setToolTipText(history.canRedo()
        ? "Refazer: " + history.redoLabel() + " (" + shortcutName() + "⇧Z)"
        : "Nada a refazer");
  }

  /**
   * Só avisa o que o diff por linha NÃO consegue mostrar. Aviso que aparece
   * sempre ensina a ignorar a barra, então diferença de EOL/BOM/quebra final só
   * é dita quando o conteúdo das linhas é idêntico — aí é a única diferença que
   * existe, e sem esse texto a tela pareceria dizer "arquivos iguais" enquanto
   * os bytes diferem.
   */
  private String buildWarning() {
    // Este aviso não depende de haver diferença: é sobre o que a GRAVAÇÃO vai
    // fazer, e tem de aparecer antes de o usuário clicar em salvar.
    List<String> mixed = new ArrayList<>();
    if (left.mixedEol()) {
      mixed.add(left.name());
    }
    if (right.mixedEol()) {
      mixed.add(right.name());
    }
    if (!mixed.isEmpty()) {
      return "EOL misto em " + String.join(" e ", mixed)
          + ": salvar vai uniformizar o arquivo inteiro.";
    }
    if (!result.identical()) {
      return "";
    }
    List<String> diffs = new ArrayList<>();
    if (!left.eolLabel().equals(right.eolLabel())) {
      diffs.add("fim de linha (" + left.eolLabel() + " × " + right.eolLabel() + ")");
    }
    if (left.bom() != right.bom()) {
      diffs.add("BOM");
    }
    if (left.trailingNewline() != right.trailingNewline()) {
      diffs.add("quebra de linha no fim");
    }
    if (diffs.isEmpty()) {
      return "";
    }
    return "Linhas idênticas; difere só em: " + String.join(", ", diffs) + ".";
  }

  // ─── Arquivos ───────────────────────────────────────────────────────────

  // ─── Modos ──────────────────────────────────────────────────────────────

  /** Mostra a tela inicial (as duas opções). */
  private void showStart() {
    cards.show(root, "start");
    setTitle("MageDiff");
  }

  /** Modo arquivo: a tela que já existia, com a barra de caminhos. */
  private void startFileMode() {
    closeRepo();
    filesList.setVisible(false);
    scroll.setColumnHeaderView(pathBar);
    cards.show(root, "diff");
    recompute();
    revalidate();
  }

  /**
   * Modo Git: escolhe a pasta, abre o repositório e lista o que difere.
   * Aceita qualquer subpasta — o JGit sobe até achar o `.git`, como o próprio
   * git faz, então não é preciso acertar a raiz.
   */
  private void openGitFolder() {
    JFileChooser chooser = new JFileChooser();
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    chooser.setDialogTitle("Escolher a pasta do repositório");
    if (repo != null) {
      chooser.setCurrentDirectory(repo.workTree().toFile());
    }
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    openRepo(chooser.getSelectedFile().toPath());
  }

  /** Abre um repositório sem diálogo — usado pelo `--git <pasta>` e pelo teste
   * visual, que não pode parar num seletor de arquivos. */
  void openRepo(Path folder) {
    try {
      GitRepo opened = GitRepo.open(folder);
      if (opened == null) {
        JOptionPane.showMessageDialog(this,
            "Não há repositório Git nesta pasta (nem acima dela).",
            "Sem repositório", JOptionPane.WARNING_MESSAGE);
        return;
      }
      closeRepo();
      repo = opened;
      filesList.setVisible(true);
      scroll.setColumnHeaderView(gitHeader);
      gitHeader.setRepo(repo.workTree() + "   ·   branch " + repo.currentBranch(),
          repo.sides());
      cards.show(root, "diff");
      refreshGitChanges();
      revalidate();
    } catch (Exception e) {
      JOptionPane.showMessageDialog(this, "Não foi possível abrir o repositório:\n"
          + e.getMessage(), "Erro", JOptionPane.ERROR_MESSAGE);
    }
  }

  /** Relista os arquivos que diferem entre os dois lados escolhidos. */
  private void refreshGitChanges() {
    if (repo == null) {
      return;
    }
    try {
      filesList.setFiles(repo.changes(gitHeader.from(), gitHeader.to()));
      setTitle("MageDiff — " + repo.workTree().getFileName());
    } catch (Exception e) {
      JOptionPane.showMessageDialog(this, "Não foi possível comparar:\n" + e.getMessage(),
          "Erro", JOptionPane.ERROR_MESSAGE);
    }
  }

  /**
   * Carrega os dois lados de um arquivo da lista. Lado sem o arquivo (adicionado
   * ou removido) entra vazio — que é exatamente como o diff já representa "não
   * existe deste lado".
   */
  private void openGitFile(GitRepo.ChangedFile file) {
    if (repo == null) {
      return;
    }
    try {
      TextFile a = repo.file(gitHeader.from(), file.oldPath());
      TextFile b = repo.file(gitHeader.to(), file.path());
      // Lado onde o arquivo não existe entra vazio COM rótulo: "(sem arquivo)"
      // não diz nada, "(não existe em HEAD)" explica o lado todo cinza.
      left = a != null ? a : absent(gitHeader.from());
      right = b != null ? b : absent(gitHeader.to());
      history.clear();
      view.clearSelection();
      clearTrail();
      recompute();
    } catch (Exception e) {
      JOptionPane.showMessageDialog(this, "Não foi possível ler o arquivo:\n" + e.getMessage(),
          "Erro", JOptionPane.ERROR_MESSAGE);
    }
  }

  /** Lado vazio e read-only, nomeado pelo lado a que pertence. */
  private static TextFile absent(GitRepo.Side side) {
    return TextFile.ofBytes(new byte[0], java.nio.charset.StandardCharsets.UTF_8,
        "(não existe em " + side.label() + ")");
  }

  private void closeRepo() {
    if (repo != null) {
      repo.close();
      repo = null;
    }
  }

  /** Arquivo solto: carrega no lado onde caiu. */
  private void dropFile(DiffView.Side side, Path path) {
    loadPath(side, path.toString());
    flash("Aberto " + path.getFileName() + " à " + (side == DiffView.Side.LEFT ? "esquerda" : "direita"));
  }

  /** Caminho digitado/colado na barra de caminho. */
  private void loadPath(DiffView.Side side, String path) {
    try {
      TextFile loaded = TextFile.read(Path.of(path));
      if (side == DiffView.Side.LEFT) {
        left = loaded;
      } else {
        right = loaded;
      }
      history.clear();
      view.clearSelection();
      clearTrail();
      recompute();
    } catch (IOException ex) {
      JOptionPane.showMessageDialog(this, "Não foi possível ler:\n" + ex.getMessage(),
          "Erro", JOptionPane.ERROR_MESSAGE);
    }
  }

  /**
   * Trocar a codificação relê o arquivo do disco: o texto em memória já foi
   * decodificado e reinterpretá-lo daria lixo. Como isso descarta alteração não
   * gravada, pergunta antes.
   */
  private void reopenWithCharset(DiffView.Side side, java.nio.charset.Charset charset) {
    TextFile target = side == DiffView.Side.LEFT ? left : right;
    if (!target.loaded() || charset.equals(target.charset())) {
      return;
    }
    if (target.dirty() && !confirmDiscard(
        "Trocar a codificação relê o arquivo e descarta as alterações não gravadas deste lado.")) {
      pathBar.update(left, right);
      return;
    }
    try {
      TextFile reread = TextFile.read(target.path(), charset);
      if (side == DiffView.Side.LEFT) {
        left = reread;
      } else {
        right = reread;
      }
      history.clear();
      clearTrail();
      recompute();
      flash("Relido como " + charset.displayName());
    } catch (IOException e) {
      JOptionPane.showMessageDialog(this, "Não foi possível reler:\n" + e.getMessage(),
          "Erro", JOptionPane.ERROR_MESSAGE);
      pathBar.update(left, right);
    }
  }

  private void open(boolean isLeft) {
    JFileChooser chooser = new JFileChooser();
    TextFile current = isLeft ? left : right;
    if (current.loaded()) {
      chooser.setCurrentDirectory(current.path().getParent().toFile());
    }
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    File chosen = chooser.getSelectedFile();
    try {
      TextFile loaded = TextFile.read(chosen.toPath());
      if (isLeft) {
        left = loaded;
      } else {
        right = loaded;
      }
      recompute();
    } catch (IOException ex) {
      JOptionPane.showMessageDialog(this, "Não foi possível ler:\n" + ex.getMessage(),
          "Erro", JOptionPane.ERROR_MESSAGE);
    }
  }

  /**
   * Troca os dois lados de posição. O histórico é ZERADO junto: os snapshots
   * guardam o par (esquerda, direita), e desfazer depois da troca devolveria o
   * conteúdo de um arquivo para dentro do outro.
   */
  private void swapSides() {
    TextFile old = left;
    left = right;
    right = old;
    history.clear();
    view.clearSelection();
    recompute();
    flash("Lados trocados — histórico de desfazer zerado");
  }

  /** Relê os dois arquivos do disco. Alteração não gravada é perdida, então
   * pergunta antes — é a única ação do app que descarta trabalho sem gravar. */
  private void reload() {
    boolean pending = (left.dirty() && left.loaded()) || (right.dirty() && right.loaded());
    if (pending) {
      int answer = JOptionPane.showConfirmDialog(this,
          "Há alterações não gravadas. Recarregar vai descartá-las. Continuar?",
          "Recarregar", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
      if (answer != JOptionPane.YES_OPTION) {
        return;
      }
    }
    left = reread(left);
    right = reread(right);
    history.clear();
    view.clearSelection();
    recompute();
    flash("Arquivos relidos do disco");
  }

  private TextFile reread(TextFile file) {
    if (!file.loaded()) {
      return file;
    }
    try {
      return TextFile.read(file.path());
    } catch (IOException e) {
      JOptionPane.showMessageDialog(this, "Não foi possível reler:\n" + e.getMessage(),
          "Erro", JOptionPane.ERROR_MESSAGE);
      return file;
    }
  }

  private void save(boolean isLeft) {
    TextFile target = isLeft ? left : right;
    if (target.readOnly()) {
      flash("Este lado é um commit — não há onde gravar.");
      return;
    }
    Path destination = target.path();
    // Lado sem arquivo (veio de "Nova comparação") precisa de destino: salvar
    // vira salvar-como em vez de não fazer nada em silêncio.
    if (destination == null) {
      JFileChooser chooser = new JFileChooser();
      chooser.setDialogTitle(isLeft ? "Salvar esquerda como" : "Salvar direita como");
      if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
        return;
      }
      destination = chooser.getSelectedFile().toPath();
    }
    try {
      target.write(destination);
      pathBar.update(left, right);
      refreshStatus();
      setTitle(String.format("MageDiff — %s%s × %s%s",
          left.name(), left.dirty() ? " *" : "",
          right.name(), right.dirty() ? " *" : ""));
    } catch (IOException ex) {
      JOptionPane.showMessageDialog(this, "Não foi possível gravar:\n" + ex.getMessage(),
          "Erro", JOptionPane.ERROR_MESSAGE);
    }
  }

  // ─── Entrada ────────────────────────────────────────────────────────────

  public static void main(String[] args) throws Exception {
    List<String> files = new ArrayList<>();
    String renderTo = null;
    String gitFolder = null;
    for (int i = 0; i < args.length; i++) {
      if ("--render".equals(args[i]) && i + 1 < args.length) {
        renderTo = args[++i];
      } else if ("--git".equals(args[i]) && i + 1 < args.length) {
        gitFolder = args[++i];
      } else {
        files.add(args[i]);
      }
    }
    final String repoFolder = gitFolder;

    TextFile left = load(files, 0);
    TextFile right = load(files, 1);

    if (renderTo != null) {
      // Com display, vale mais renderizar a janela inteira (mostra a barra e o
      // estado dos botões); sem display, ainda dá para conferir a área de diff.
      if (java.awt.GraphicsEnvironment.isHeadless()) {
        renderToPng(left, right, Path.of(renderTo));
      } else {
        renderWindowToPng(left, right, Path.of(renderTo), 1320, 660, repoFolder);
      }
      System.exit(0);
    }

    // No macOS o menu vai para a barra do sistema, como em qualquer app nativo.
    // Tem de ser definido ANTES de qualquer componente Swing existir.
    System.setProperty("apple.laf.useScreenMenuBar", "true");
    System.setProperty("apple.awt.application.name", "MageDiff");

    SwingUtilities.invokeLater(() -> {
      try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
      } catch (Exception ignored) {
        // LAF padrão serve; não é motivo para não abrir.
      }
      MageDiffApp app = new MageDiffApp(left, right);
      if (repoFolder != null) {
        app.openRepo(Path.of(repoFolder));
      }
      app.setVisible(true);
    });
  }

  /**
   * Caminho ausente ou ilegível abre o lado vazio, com aviso no console, em vez
   * de derrubar o programa: quem passou o arquivo errado quer corrigir pelo botão
   * "Abrir", não ler um stack trace. (É também o que faz o run config do IDEA
   * funcionar antes de {@code ./run.sh demo} criar as cópias.)
   */
  private static TextFile load(List<String> files, int index) {
    if (index >= files.size()) {
      return TextFile.empty();
    }
    try {
      return TextFile.read(Path.of(files.get(index)));
    } catch (IOException | RuntimeException e) {
      System.err.printf("Não foi possível abrir %s: %s%n", files.get(index), e.getMessage());
      return TextFile.empty();
    }
  }

  /**
   * Pinta a área de diff num PNG sem abrir janela. Existe para conferir o visual
   * de forma reproduzível (e para gerar imagem de documentação) — um comparador
   * cujo layout só pode ser verificado a olho não é verificável.
   */
  /**
   * Renderiza a JANELA inteira (barra, cabeçalho, diff, rodapé) sem chegar a
   * mostrá-la: {@code addNotify} cria o peer e {@code validate} roda o layout,
   * mas quem exibe é o {@code setVisible}, que não é chamado.
   *
   * <p>Serve para conferir a interface de forma reproduzível — e é o que permite
   * revisar espaçamento e estado dos botões sem depender de screenshot manual.
   */
  private static void renderWindowToPng(TextFile left, TextFile right, Path out, int width,
      int height, String repoFolder) throws Exception {
    final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    SwingUtilities.invokeAndWait(() -> {
      try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
      } catch (Exception ignored) {
        // irrelevante para o render
      }
      MageDiffApp app = new MageDiffApp(left, right);
      if (repoFolder != null) {
        app.openRepo(Path.of(repoFolder));
      }
      app.setSize(width, height);
      app.addNotify();
      app.validate();
      if (app.view.hunkCount() > 0) {
        app.view.goToHunk(0);
      }
      app.validate();
      Graphics2D g = image.createGraphics();
      // Root pane, não content pane: o content pane exclui a barra de menus, e a
      // faixa que sobrava embaixo do PNG parecia defeito da tela sendo defeito
      // do render.
      app.getRootPane().paint(g);
      g.dispose();
      app.dispose();
    });
    ImageIO.write(image, "png", out.toFile());
    System.out.printf("%s — janela %dx%d%n", out, width, height);
  }

  private static void renderToPng(TextFile left, TextFile right, Path out) throws IOException {
    DiffView view = new DiffView();
    Result result = DiffEngine.diff(left.lines(), right.lines(), true);
    view.setData(result, left.lines(), right.lines());
    int width = 1280;
    int height = Math.max(view.rowHeight(), result.rows().size() * view.rowHeight());
    view.setSize(width, height);
    if (!result.hunks().isEmpty()) {
      view.goToHunk(0);
    }

    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    view.paint(g);
    g.dispose();
    ImageIO.write(image, "png", out.toFile());
    System.out.printf("%s — %dx%d, +%d -%d, %d bloco(s)%n",
        out, width, height, result.added(), result.removed(), result.hunks().size());
  }
}
