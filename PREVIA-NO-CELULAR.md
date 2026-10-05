# Usar o POKECARD no iPhone (e em qualquer navegador)

O aplicativo de verdade roda também no navegador: mesmas telas, mesmas cores,
mesmos botões. No **iPhone** ele se instala pela Tela de Início e vira um app
com ícone próprio, tela cheia e funcionando sem internet.

O endereço (o mesmo para todo mundo):

```
https://fernandossb.github.io/POKECARD/
```

## Instalar no iPhone

1. Abra o endereço acima no **Safari** (precisa ser o Safari, não o Chrome).
2. Toque no botão **Compartilhar** (o quadrado com a seta para cima, na barra de baixo).
3. Role e toque em **Adicionar à Tela de Início**, depois em **Adicionar**.
4. Abra o app pelo **ícone novo**.

**Faça isso antes de cadastrar cartas.** Duas razões:

- O app instalado guarda a coleção **separado do Safari**. O que for cadastrado
  numa aba do Safari não aparece no ícone da Tela de Início.
- O Safari apaga os dados de um site que fica **7 dias sem ser aberto**. O app
  instalado não sofre isso.

## O que funciona no iPhone

Tudo:

- Coleção, Fichário virtual, Pokédex, decks, troféus, wishlist, repetidas e preços em reais.
- **Scanner pela câmera**: a Pokébola do meio da barra abre a câmera, você encaixa a carta
  na moldura e o app a reconhece, igual ao Android (veja abaixo).
- Funciona sem internet depois da primeira abertura (os preços e fotos novos precisam de internet).
- Exportar para PDF, Excel e Liga Pokémon, e o backup, pelo botão **Compartilhar** do iPhone
  (salvar em Arquivos, iCloud Drive, AirDrop, WhatsApp).

### A câmera no iPhone

- Na primeira vez o iPhone pede a **permissão da câmera** (toque em Permitir) e o app baixa
  o leitor de texto, uns **30 MB**, uma vez só — com Wi-Fi, de preferência. Depois fica guardado
  no aparelho e funciona sem internet.
- Precisa do **iOS 16.4 ou mais novo** para ser rápido (em versões anteriores ainda funciona,
  mais devagar).
- Encaixe a carta **inteira** na moldura, com luz boa e **sem reflexo**: o iPhone não deixa
  o app baixar o brilho, então o aviso de reflexo pede para você mudar o ângulo.
- Cada leitura leva uns 2 a 4 segundos (o Android lê um pouco mais depressa).
- A leitura é feita no próprio aparelho: a foto da câmera **nunca sai do iPhone**.
- Se a câmera não abrir: confira em **Ajustes → Safari → Câmera** (ou, no app instalado,
  **Ajustes → POKECARD**) se está em *Perguntar* ou *Permitir*. Sem a câmera, a Pokébola
  continua servindo como **Buscar carta**: dá para achar pelo nome ou número.

## Backup (importante)

No iPhone o backup **não é automático**. Toque no **PB** (canto do Início) →
**Exportar backup** e salve em **Arquivos**. O app avisa quando passa de 14 dias sem backup
(5 dias, se estiver aberto no Safari sem instalar).

Para levar a coleção do Android para o iPhone (ou o contrário): exporte o backup num e
use **Importar backup** no outro.

## Atualizações

O app se atualiza sozinho: cada vez que você abre, ele confere se há versão nova. Para
forçar: **PB → Atualizar o app**.

## Para o dono do projeto

### Como o site é publicado

O robô **Publicar prévia no navegador** (`.github/workflows/publicar-previa.yml`)
roda a cada envio para `main` que mexa na pasta do app, e também à mão em
**Actions → Publicar prévia no navegador → Run workflow**. Ele publica no GitHub Pages
e carimba a versão do app no `sw.js` (service worker), para o navegador instalar a
versão nova e apagar o cache da anterior.

Antes de empacotar, o robô roda `node scripts/baixar-ocr.mjs`: baixa para `www/ocr/` o leitor
de texto da câmera (ONNX Runtime Web 1.17.3 + PaddleOCR PP-OCRv3, ~30 MB), em versão fixa
e conferindo o checksum. Essa pasta **não vai para o Git nem para o APK** (no Android quem
lê é o ML Kit). Para testar a câmera no seu computador: rode o mesmo comando e sirva a pasta
`www` por `localhost` (a câmera só abre em https ou localhost).

Não precisa configurar nada: o robô ativa o Pages na primeira vez (`enablement: true`).
Só é preciso que as permissões de escrita estejam liberadas: **Settings → Actions → General →
Workflow permissions → Read and write permissions**. Se aparecer *"Get Pages site failed /
Not Found"*, ligue à mão em **Settings → Pages → Source → GitHub Actions** e rode de novo.

### Ao mudar um arquivo do app

Os arquivos têm `?v=NN` no `index.html`, e o service worker guarda cada endereço com o
número. **Mude o número ao mudar o arquivo**, senão quem já abriu o app continua com o
arquivo antigo até a próxima versão.

### Cuidados ao compartilhar

- O site é público: qualquer pessoa com o link usa o app. A coleção de cada um fica só
  no aparelho dele; ninguém vê a coleção de ninguém.
- O app usa nomes e imagens de cartas Pokémon, que são de The Pokémon Company /
  Nintendo. Entre amigos não tem problema; divulgar em larga escala tem risco de
  direitos de imagem. (Também é por isso que ele não vai para a App Store.)
