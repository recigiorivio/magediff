package br.com.rivio.magediff;

import java.util.List;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.diff.SequenceComparator;

/**
 * Liga uma {@code List<String>} ao motor de diff do JGit.
 *
 * <p>O JGit trabalha sobre {@link Sequence} — normalmente {@code RawText}, que
 * lê um buffer de bytes e fatia em linhas. Aqui não serve: o arquivo já foi
 * lido, decodificado e fatiado pelo {@link TextFile}, que também guarda EOL e
 * BOM para a gravação. Passar bytes de novo faria a decodificação acontecer
 * duas vezes, por dois caminhos diferentes — e é assim que um comparador
 * começa a mostrar uma coisa e gravar outra.
 *
 * <p>Serve para os dois níveis de comparação: linhas de um arquivo e tokens de
 * uma linha. São a mesma estrutura, só muda o que cada elemento significa.
 */
final class LineSequence extends Sequence {

  static final SequenceComparator<LineSequence> COMPARATOR = new SequenceComparator<>() {
    @Override
    public boolean equals(LineSequence a, int ai, LineSequence b, int bi) {
      return a.get(ai).equals(b.get(bi));
    }

    @Override
    public int hash(LineSequence seq, int ptr) {
      // O HistogramDiff usa este hash para contar ocorrências e escolher as
      // linhas raras como âncora; `String.hashCode` distribui bem o bastante.
      return seq.get(ptr).hashCode();
    }
  };

  private final List<String> items;

  LineSequence(List<String> items) {
    this.items = items;
  }

  String get(int index) {
    return items.get(index);
  }

  @Override
  public int size() {
    return items.size();
  }
}
