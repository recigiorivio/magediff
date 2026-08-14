package br.com.rivio.magediff;

/**
 * Um arquivo que difere entre os dois lados de uma comparação.
 *
 * <p>Vive fora do {@link GitRepo} porque serve aos dois modos que listam
 * arquivos: repositório e pasta. Quando era um tipo aninhado do Git, comparar
 * pastas exigiria ou duplicar o registro ou importar o modo Git para dentro de
 * um lugar que não tem nada com Git.
 *
 * <p>{@code oldPath} só difere de {@code path} em renomeação — no modo pasta os
 * dois são sempre iguais, porque a correspondência ali é pelo caminho relativo.
 */
public record ChangedFile(String path, String oldPath, ChangeKind kind) {

  public enum ChangeKind {
    ADICIONADO, REMOVIDO, ALTERADO, RENOMEADO
  }

  public static ChangedFile added(String path) {
    return new ChangedFile(path, path, ChangeKind.ADICIONADO);
  }

  public static ChangedFile removed(String path) {
    return new ChangedFile(path, path, ChangeKind.REMOVIDO);
  }

  public static ChangedFile modified(String path) {
    return new ChangedFile(path, path, ChangeKind.ALTERADO);
  }

  @Override
  public String toString() {
    return path;
  }
}
