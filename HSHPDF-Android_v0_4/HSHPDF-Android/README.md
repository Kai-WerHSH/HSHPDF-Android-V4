# HSHPDF para Android (nativo: Kotlin + Jetpack Compose + MuPDF)

**Criador da fonte:** Henrique de Souza Henrique
**Coautor e assistente de IA:** Claude (Anthropic)

## Novo na 0.4 — leitor igual ao do Mihon
Abrir um quadrinho (CBZ/CBR/imagens) já entra no **modo leitura**: só as páginas, tela cheia, 100% na largura, coladas, sem bordas.
No primeiro uso aparece uma dica com as zonas de toque. Para os outros arquivos use o botão **Ler** na barra de cima.

- **Toque no CENTRO:** mostra/esconde as barras (como no Mihon).
  - *Barra de cima:* seta de voltar, nome do arquivo, "Página X de N" e menu ⋮ (Páginas, Editar/anotar, Exportar/converter, Zoom 100%, Sair).
  - *Barra de baixo:* botões **início / fim**, **controle deslizante** para pular para qualquer página (com o número atual e o total) e quatro ícones:
    **Modo** (tira longa, tira com lacunas, paginado esquerda→direita, direita→esquerda, vertical), **Orientação** (toque para alternar
    padrão/automática/retrato/paisagem), **Fundo** (toque para alternar preto/cinza/branco/auto) e **Ajustes** (engrenagem).
- **Toque nas LATERAIS:** esquerda = voltar, direita = avançar (na tira rola uma tela; nos modos paginados vira a página).
- **Toque duplo:** zoom. **Pinça:** zoom livre. **Voltar** (botão ou gesto do Android) ou a seta da barra de cima: sai da leitura.
- **Engrenagem (Ajustes):** folha com 3 abas como no Mihon — *Modo de leitura* (modo, orientação, zonas de toque, margem lateral, toque duplo),
  *Geral* (cor de fundo incl. **Auto** = cor das bordas da página, número da página, conteúdo na área do recorte, manter a tela ligada, animar páginas)
  e *Filtro* (brilho, nível de cinza, invertido).
- **Continua de onde parou:** o app lembra a última página lida de cada arquivo.
- Atalhos extras: tocar 3 vezes abre os Ajustes; arrastar para o lado (na tira, sem zoom) também sai.

**Armazenamento:** os arquivos que você abre **não são mais copiados** para dentro do app (a 0.2 copiava, por isso o app crescia).
O app abre direto do original; se o aplicativo de origem não permitir (alguns arquivos de nuvem), usa uma cópia **temporária** apagada ao fechar o documento.
Ao iniciar, o app apaga cópias antigas da 0.2. Em **Ajustes > Armazenamento** aparecem os tamanhos, "Limpar cache" e "Apagar edições salvas".
Observação: se você mover/apagar o arquivo original, as edições salvas dele não conseguem ser restauradas.

## Versão 0.2 — o que tem
**Leitura**
- Abre **PDF, EPUB, XPS, CBZ, CBR (RAR) e imagens**; também por "Abrir com" e "Compartilhar" de outros apps.
- Rolagem contínua, zoom com dois dedos / toque duplo, miniaturas (menu ⋮), buscar texto (PDF/EPUB), ir para a página.
- **Tela cheia**: esconde hora, bateria e botões do sistema (deslize na borda para ver por um instante). Liga/desliga no menu ⋮.
- **Somente leitura** (menu ⋮; opcional automático para quadrinhos/EPUB em Ajustes).

**Edição (botão "Editar")** — tudo desfaz/refaz (↶ ↷), tudo fica editável:
- Selecionar (mover, redimensionar pelas alças, mudar cor/opacidade/espessura, duplicar, excluir).
- Lápis suave, marca-texto, **borracha precisa** (corta o traço só onde passa).
- Formas: retângulo, elipse, **círculo**, linha, seta, **seta curva**, polígono, polilinha (com preenchimento).
- Texto, **notas post-it** (5 cores, redimensionável), **carimbos** (prontos, criar de texto, importar imagem), **imagem com opacidade**.
- **Salvamento automático**: ao reabrir o arquivo, suas edições voltam (menu ⋮ → "Descartar edições" volta ao original).

**Páginas (botão "Páginas")**: grade com seleção múltipla (toque longo): girar, excluir, duplicar, mover, extrair,
inserir em branco, **inserir só algumas páginas de outro arquivo** (ex.: 1-5,8), desfazer/refazer.

**Exportar / converter (menu ⋮ → Exportar)**: PDF, CBZ, EPUB (layout fixo) ou ZIP de imagens; todas as páginas,
só as selecionadas ou um intervalo; **qualidade JPEG e tamanho** à escolha (resolve o problema dos arquivos inchados).
**Lote** (tela inicial): vários arquivos de uma vez → PDF/CBZ/EPUB, ou juntar tudo em um só. **Imagens → PDF** e **Novo em branco** na tela inicial.

**Outros**: abas (vários documentos), captura de tela de uma área (salvar/compartilhar/copiar), som de conclusão, tema claro/escuro e cor de destaque.

## O que NÃO tem (por limitação técnica, não por esquecimento)
- Editar o texto original do PDF; PDF → Word; senha/criptografia; OCR.
- PDFs exportados guardam cada página como **imagem JPEG** (sem texto selecionável). O arquivo original nunca é alterado.
- Anotações que já vêm dentro de um PDF aparecem, mas não são editáveis (só as feitas no app).
- Não cria CBR (RAR é formato fechado): use CBZ. CB7/7z ainda não.
- Busca de texto só em PDF/EPUB (CBZ/CBR são imagens). Realce de busca pode ficar deslocado em páginas giradas.

## Como gerar o APK sem instalar nada (GitHub Actions)
1. Crie um repositório no GitHub e envie **todo o conteúdo desta pasta** (inclusive a pasta oculta `.github`).
2. Aba **Actions** → "Compilar APK" roda sozinho (ou *Run workflow*). Leva uns 5–10 minutos.
3. Quando ficar verde, baixe o artefato **HSHPDF-apk** → dentro está `app-debug.apk`.
4. Passe para o celular e instale (permita "fontes desconhecidas"). Instalar por cima mantém seus dados.

## Se a compilação falhar
Escrito sem poder compilar no ambiente de criação. Abra a execução ❌ → passo vermelho → copie as linhas que começam
com `e:` e envie para análise; costuma ser correção de uma linha.

## Observações
- MuPDF é **AGPL**: ok para uso pessoal; se um dia distribuir o app, a licença exige abrir o código.
- APK "debug": assinado com chave de teste, instalável, suficiente para uso pessoal.
