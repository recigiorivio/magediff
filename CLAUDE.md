# CLAUDE.md — magediff

App desktop **Java 17 + Swing** que compara e faz merge de dois arquivos, no
espírito do Beyond Compare. O alinhamento é o **HistogramDiff do Eclipse JGit**;
o `run.sh` resolve as dependências uma vez para `target/lib` e depois o ciclo é
só `javac`/`java`. `./package-app.sh` gera o `.app` nativo.

Uso e atalhos estão no [`README.md`](README.md). Aqui fica o que o código não diz
sozinho.

## Comandos

```bash
./run.sh test      # gate obrigatório antes de commitar (compila + verifica)
./run.sh demo      # abre os samples em cópia
mvn package        # fat jar autocontido (3,5 MB)
./package-app.sh   # .app nativo com runtime embutido (56 MB)
```

O fat jar precisa dos filtros de `META-INF/*.SF` no shade: **os jars do JGit são
assinados**, e remontar o conteúdo invalida a assinatura — sem o filtro, a JVM
recusa o jar com "Invalid signature file digest".

Não há JUnit: `EngineSmokeTest` é um `main()` com asserções impressas, e o
surefire está desligado no `pom.xml` por isso. Rodar os testes é `./run.sh test`,
nunca `mvn test`.

## Onde mexer com cuidado

| Se você for mexer em… | Saiba antes |
|---|---|
| `Merge` | É o **único** ponto do programa que altera o arquivo do usuário. Está separado da UI justamente para o teste exercitar a mesma chamada que a seta faz — se você inline a mutação de volta no `MageDiffApp`, o teste passa a validar uma cópia da lógica |
| `TextFile.write` | EOL/BOM/quebra-final são reescritos como vieram. "Simplificar" isso faz um merge de uma linha virar 400 linhas mudadas no diff do git |
| `UndoStack.restore` | Restaura **sobre as mesmas listas** (`clear` + `addAll`). Trocar a referência deixa `DiffView` e `TextFile` apontando para o conteúdo antigo |
| `DiffEngine.emit` | Os intervalos do `Hunk` são semiabertos nos dois lados — é o que faz inserção e remoção caírem no mesmo caminho de código |
| `DiffView` | Um componente só pinta os DOIS lados. Dividir em dois painéis reintroduz a dessincronia de rolagem que essa escolha elimina |

## Decisões que já foram medidas

**A rolagem horizontal é interna, não do `JScrollPane`.** Se fosse do scroll
pane, uma linha longa empurraria a coluna das setas para fora da tela — e seta
que não se alcança não serve para merge. Por isso `getScrollableTracksViewportWidth()`
devolve `true` e o `hOffset` é aplicado só dentro das duas colunas de texto.

**O alinhamento é do JGit; o resto não.** `DiffAlgorithm.diff` devolve um
`EditList` de `Edit(beginA, endA, beginB, endB)` — exatamente o intervalo
semiaberto do `Hunk`, inclusive para inserção e remoção puras. O `EditList` traz
**só as mudanças**: o contexto entre elas é preenchido em `emit()` com dois
cursores. O que ficou aqui é o que a lib não faz: virar linhas de tela, realce
por token, colapso de contexto e a distinção entre diferença real e diferença só
de espaço.

**Por que Histogram e não Myers** (os dois vêm no JGit): quando há mais de uma
solução mínima, Myers pode partir uma remoção em dois blocos ADJACENTES — na
tela, duas setas para uma mudança só. Medido no exemplo canônico do patience
diff: Myers 5 blocos (dois colados), Histogram 4 e nenhum colado. Há teste de
regressão (`contiguousBlocks`) checando a propriedade, não os índices — trocar o
algoritmo de volta quebra o teste.

**`LineSequence` existe para não decodificar duas vezes.** O JGit normalmente usa
`RawText`, que fatia um buffer de bytes. Mas o arquivo já foi lido e fatiado pelo
`TextFile`, que guarda EOL/BOM para a gravação; passar bytes de novo faria a
decodificação por dois caminhos diferentes — e é assim que um comparador começa a
mostrar uma coisa e gravar outra.

**`Kind.MINOR` existe por causa do "ignorar espaços".** Duas linhas alinhadas
como iguais podem ter texto diferente; em vez de mostrar uma delas e fingir, a
row guarda os dois textos e é pintada num tom neutro. Não conta como diferença,
não é navegável e não tem seta — quem ligou a opção disse que não quer parar ali.

**Tokenização com pontuação isolada.** Palavra e espaço agrupados, todo o resto
caractere a caractere. Agrupando `",` num token só, uma linha que apenas ganhou
vírgula marcava a aspa junto, e o olho procura uma mudança que não existe.

**EOL misto normaliza ao salvar, e isso é dito antes.** É a única exceção à
regra de preservar bytes. Fica em `TextFile.mixedEol()` e aparece na barra de
status — aviso depois de gravar não serve para nada.

**Cada atalho tem UM dono.** Os aceleradores dos itens de menu registram ⌘Z, ⌘C,
⌘V, ⌥→, ⌥←, F5, F7 e F8; o `InputMap` do root pane fica só com o que o menu não
cobre (⌘↑/⌘↓, ⌘Y, Esc). Chegou a haver **nove teclas nos dois mapas** — e se o
mesmo ⌥→ disparasse duas vezes, o app mesclaria dois blocos num toque. Há um
script de conferência no histórico desta sessão (`DupCheck`) que lista a
interseção dos dois mapas; hoje é vazia.

`⌘↑`/`⌘↓` duplicam `F7`/`F8` porque o macOS captura as teclas de função por
padrão.

**Ícones são vetor, não unicode.** Glifo de fonte (⟲, ⇄) muda de tamanho, peso e
alinhamento entre plataformas, e vira caixinha quando a fonte não o tem.
`Icons.java` desenha tudo numa caixa nominal de 16×16 e recebe a cor do estado
do botão. Barra só de ícone exige tooltip em TODOS — sem isso o custo de
adivinhar passa para o usuário.

**O rastro da cópia é protegido do colapso.** `DiffEngine.diff` recebe um
intervalo `[keepFrom,keepTo)` de linhas da esquerda que nunca colapsa. Sem ele, o
trecho recém-mesclado vira contexto e o "só mudanças" o esconde — levando junto o
↶. Detalhe medido no teste: o problema só aparece quando SOBRA diferença no
arquivo; com uma mudança só, depois da cópia os arquivos ficam idênticos e o
colapso nem roda. O `trailFrom/trailTo` é declarado ANTES do recompute (ele é
entrada do diff, não consequência) e limpo antes de qualquer outra ação.

**O ↶ inline não é enfeite.** Depois de uma cópia o bloco deixa de existir (os
lados ficaram iguais) e a seta some. `DiffView.undoRow` marca onde o conteúdo
caiu e desenha ali o desfazer; some no próximo diff que não venha de uma cópia.
O cálculo (`rowOfLine`) é feito DEPOIS do recompute — antes dele as rows ainda
são as antigas.

**O modo Git usa o JGit como Git, não só como diff.** `GitRepo` abre o
repositório (`findGitDir` sobe até achar o `.git`, como o próprio git), lista
branches, roda `DiffFormatter.scan` entre dois lados e lê blobs. Nada de chamar o
executável `git`: ele pode não estar no PATH, e a saída dele seria texto para
parsear de volta.

**Lado de revisão é read-only por construção, não por regra de tela.** O
`TextFile` de um blob nasce sem `path` — sem caminho não há onde gravar, e
`readOnly()` é derivado disso. Gravar e copiar-para-cá são bloqueados no
`MageDiffApp`; se a checagem sumisse, o merge alteraria só a memória e sumiria no
clique seguinte, dando a impressão de que foi aplicado.

**`--git <pasta>` abre o modo Git sem diálogo.** Existe para o terminal e porque
o teste visual não pode parar num seletor de arquivos.

⚠️ **slf4j-nop tem que ser da MESMA linha do slf4j-api que o JGit traz (1.7.x).**
Com o 2.x o binding é por ServiceLoader e o 1.7 procura
`org.slf4j.impl.StaticLoggerBinder` — versões cruzadas fazem o NOP não ligar, e o
aviso aparece no console assim mesmo. Aconteceu.

## Verificação sem clicar

`--render` pinta a interface num PNG sem exibir janela (`addNotify` + `validate`
montam o layout; quem exibe é o `setVisible`, que não é chamado). Serve para
revisar espaçamento, cor e estado de botão de forma reproduzível — foi assim que
o "lado ausente" foi flagrado claro demais e as setas de navegação/cópia foram
descobertas ambíguas. Sem display, cai para renderizar só a área de diff.

## O que NÃO tem

Merge de 3 vias, comparação de pastas, edição caractere a caractere, DETECÇÃO
automática de codificação e busca. Nenhum desses foi cortado por dificuldade —
não foram pedidos. Se algum entrar, `DiffEngine` e `Merge` não precisam mudar.

Sobre codificação: ela é **escolhida**, não adivinhada. A barra de cabeçalho
oferece UTF-8, ISO-8859-1, windows-1252 e UTF-16, e trocar RELÊ o arquivo do
disco — reinterpretar o texto que já está em memória daria lixo. Detectar
sozinho é outro problema (heurística sobre bytes) e não foi feito.
