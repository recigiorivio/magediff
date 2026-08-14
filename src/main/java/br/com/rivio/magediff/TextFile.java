package br.com.rivio.magediff;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Um arquivo de texto carregado em memória como lista de linhas, guardando o que
 * é preciso para reescrevê-lo sem alterar nada além do conteúdo pedido.
 *
 * <p>Comparar é uma coisa, gravar é outra: o diff roda sobre linhas normalizadas
 * (sem BOM, sem CR), senão um arquivo salvo no Windows apareceria com TODAS as
 * linhas alteradas. Mas o {@code eol}, o BOM e a ausência de quebra final voltam
 * na gravação — uma ferramenta de merge que "conserta" isso sozinha faz o
 * commit do usuário virar 400 linhas mudadas sem nenhuma mudança real.
 */
public final class TextFile {

  private Path path;
  private final List<String> lines;
  /** Fim de linha usado na gravação. Não é final: a tela permite trocar. */
  private String eol;
  private final boolean bom;
  private final boolean trailingNewline;
  private final boolean mixedEol;
  /** Codificação com que o arquivo foi LIDO e com que será gravado. */
  private final Charset charset;
  private final long byteSize;
  private final Instant modifiedAt;
  private boolean dirty;

  private TextFile(Path path, List<String> lines, String eol, boolean bom,
      boolean trailingNewline, boolean mixedEol, Charset charset, long byteSize,
      Instant modifiedAt) {
    this.path = path;
    this.lines = lines;
    this.eol = eol;
    this.bom = bom;
    this.trailingNewline = trailingNewline;
    this.mixedEol = mixedEol;
    this.charset = charset;
    this.byteSize = byteSize;
    this.modifiedAt = modifiedAt;
  }

  public static TextFile empty() {
    return new TextFile(null, new ArrayList<>(), System.lineSeparator(), false, true, false,
        StandardCharsets.UTF_8, 0, null);
  }

  public static TextFile read(Path path) throws IOException {
    return read(path, StandardCharsets.UTF_8);
  }

  /**
   * @param charset como interpretar os bytes. Trocar a codificação relê o
   *     arquivo do disco — é a única forma honesta: o texto em memória já foi
   *     decodificado e reinterpretá-lo daria lixo.
   */
  public static TextFile read(Path path, Charset charset) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
    String raw = new String(bytes, charset);
    boolean bom = !raw.isEmpty() && raw.charAt(0) == '\uFEFF';
    if (bom) {
      raw = raw.substring(1);
    }
    // Detecção pelo que predomina: arquivo de EOL misto existe (merge malfeito,
    // patch aplicado à mão) e nesse caso escolher o majoritário é o menor dano.
    // Mas a gravação vai uniformizar o arquivo inteiro, então o fato é registrado
    // em `mixedEol` para a UI dizer isso ANTES de o usuário salvar.
    int crlf = countCrlf(raw);
    int lf = countChar(raw, '\n') - crlf;
    String eol = crlf > lf ? "\r\n" : "\n";
    boolean mixedEol = crlf > 0 && lf > 0;
    String normalized = raw.replace("\r\n", "\n").replace("\r", "\n");
    boolean trailingNewline = normalized.endsWith("\n");

    List<String> lines = new ArrayList<>();
    if (!normalized.isEmpty()) {
      int start = 0;
      for (int i = 0; i < normalized.length(); i++) {
        if (normalized.charAt(i) == '\n') {
          lines.add(normalized.substring(start, i));
          start = i + 1;
        }
      }
      if (start < normalized.length()) {
        lines.add(normalized.substring(start));
      }
    }
    return new TextFile(path, lines, eol, bom, trailingNewline, mixedEol, charset,
        bytes.length, attrs.lastModifiedTime().toInstant());
  }

  public void write(Path target) throws IOException {
    StringBuilder sb = new StringBuilder();
    if (bom) {
      sb.append('\uFEFF');
    }
    for (int i = 0; i < lines.size(); i++) {
      sb.append(lines.get(i));
      if (i < lines.size() - 1 || trailingNewline) {
        sb.append(eol);
      }
    }
    Files.write(target, sb.toString().getBytes(charset));
    this.path = target;
    this.dirty = false;
  }

  public List<String> lines() {
    return lines;
  }

  /** A mutação em si vive em {@link Merge} (um lugar só, testável sem UI); aqui
   * fica apenas o registro de que este lado tem alteração não gravada. */
  public void markDirty() {
    dirty = true;
  }

  /** Usado pelo {@link UndoStack}: desfazer também tem que devolver o estado de
   * "não gravado", senão o botão Salvar fica habilitado sem haver alteração. */
  public void setDirty(boolean dirty) {
    this.dirty = dirty;
  }

  public Path path() {
    return path;
  }

  public String name() {
    return path == null ? "(sem arquivo)" : path.getFileName().toString();
  }

  public boolean dirty() {
    return dirty;
  }

  public boolean loaded() {
    return path != null;
  }

  public String eolLabel() {
    return "\r\n".equals(eol) ? "CRLF" : "LF";
  }

  public boolean bom() {
    return bom;
  }

  public boolean trailingNewline() {
    return trailingNewline;
  }

  /** {@code true} quando o arquivo tem CRLF e LF misturados — salvar vai
   * uniformizar tudo para {@link #eolLabel()}. */
  public boolean mixedEol() {
    return mixedEol;
  }

  public Charset charset() {
    return charset;
  }

  public long byteSize() {
    return byteSize;
  }

  public Instant modifiedAt() {
    return modifiedAt;
  }

  /**
   * Troca o fim de linha da GRAVAÇÃO. Marca como alterado de propósito: nada
   * muda na tela (o diff roda sobre linhas normalizadas), mas os bytes do
   * arquivo vão mudar — se não marcasse, o botão de salvar ficaria cinza e a
   * troca seria silenciosamente perdida.
   */
  public void setEol(String newEol) {
    if (!newEol.equals(eol)) {
      eol = newEol;
      dirty = true;
    }
  }

  private static int countCrlf(String s) {
    int count = 0;
    int at = s.indexOf("\r\n");
    while (at >= 0) {
      count++;
      at = s.indexOf("\r\n", at + 2);
    }
    return count;
  }

  private static int countChar(String s, char c) {
    int count = 0;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == c) {
        count++;
      }
    }
    return count;
  }
}
