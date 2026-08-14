package br.com.rivio.magediff;

import java.util.ArrayList;
import java.util.List;
import br.com.rivio.magediff.DiffEngine.Hunk;

/**
 * A operação das setas, em um lugar só.
 *
 * <p>Existe como classe própria porque é o único ponto do programa que pode
 * corromper o arquivo do usuário. Se a UI tivesse a mutação inline e o teste
 * tivesse uma cópia dela, o teste passaria enquanto as duas divergissem — foi
 * exatamente esse risco que motivou extrair daqui.
 */
public final class Merge {

  private Merge() {
  }

  /** Faz a direita ficar igual à esquerda neste bloco. */
  public static void toRight(Hunk hunk, List<String> left, List<String> right) {
    replace(right, hunk.newFrom(), hunk.newTo(), left.subList(hunk.oldFrom(), hunk.oldTo()));
  }

  /** Faz a esquerda ficar igual à direita neste bloco. */
  public static void toLeft(Hunk hunk, List<String> left, List<String> right) {
    replace(left, hunk.oldFrom(), hunk.oldTo(), right.subList(hunk.newFrom(), hunk.newTo()));
  }

  /**
   * Substituição por intervalo semiaberto: o mesmo caminho serve para trocar,
   * inserir ({@code from == to}) e apagar ({@code source} vazio).
   *
   * <p>A cópia do {@code source} antes de mutar não é zelo: {@code subList}
   * devolve uma VISÃO da lista original, e em qualquer arranjo onde origem e
   * destino compartilhem o backing array o {@code clear()} apagaria o conteúdo
   * que ainda está por inserir.
   */
  public static void replaceRange(List<String> target, int from, int to, List<String> source) {
    replace(target, from, to, source);
  }

  private static void replace(List<String> target, int from, int to, List<String> source) {
    List<String> copy = new ArrayList<>(source);
    target.subList(from, to).clear();
    target.addAll(from, copy);
  }
}
