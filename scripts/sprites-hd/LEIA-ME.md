# Sprites HD da Pokédex

Transforma os 1.025 sprites animados em versões ampliadas, sem você fazer um
por um. Roda uma vez e processa tudo.

## O que instalar (uma vez só)

**1. Python** — https://www.python.org/downloads/
Na tela de instalação, **marque "Add Python to PATH"**. Sem isso o comando não
funciona depois.

**2. As duas bibliotecas** — abra o Prompt de Comando e digite:

```
pip install pillow requests
```

**3. Real-ESRGAN** — https://github.com/xinntao/Real-ESRGAN/releases

Baixe o arquivo `realesrgan-ncnn-vulkan-...-windows.zip`, extraia, e coloque a
pasta extraída **aqui dentro**, ao lado do `processar-sprites.py`.

Essa versão roda em placa de vídeo Intel e AMD comuns. Não precisa de NVIDIA.

## Como rodar

Abra o Prompt de Comando nesta pasta e digite:

```
python processar-sprites.py --apenas 25,94,6
```

Isso processa só três Pokémon, para você **conferir o resultado antes de
gastar horas**. Olhe os arquivos em `output/`. Se gostou:

```
python processar-sprites.py
```

E deixe rodando. Pode fechar e continuar depois — o que já ficou pronto não é
refeito.

## Ajustes

| comando | para quê |
|---|---|
| `--escala 4` | quanto ampliar. 4 é o padrão; 2 é mais rápido e mais leve |
| `--qualidade 90` | qualidade do arquivo final, de 1 a 100 |
| `--sem-ia` | só testa o processo, sem ampliar de verdade |
| `--apenas 1,4,7` | processa só esses números |

## O que esperar

- **~10% dos Pokémon não têm sprite animado** na fonte. Não é erro do script:
  a fonte não publica. Eles continuam usando a arte atual no app.
- Os sprites **não têm tamanho uniforme** — vão de 45×49 a 156×127. Por isso o
  script amplia por fator, preservando a proporção de cada um, em vez de
  enquadrar todos num quadrado fixo. Enquadrar deformaria os alongados.
- Cada sprite tem de 33 a 47 quadros. São mais de 36 mil quadros no total —
  reserve algumas horas.

## Quando terminar

A pasta `output/` terá os arquivos `.webp` e um `manifesto.json`.

1. Crie um repositório no GitHub (ex.: `pokecard-sprites-hd`)
2. Suba a pasta `output` inteira
3. Me diga o nome do repositório

Eu ligo no app. Já deixei o código pronto esperando: em
`app/src/main/assets/www/arte3d.js` existe uma linha `HD_BASE = ''` — é só
preencher com o endereço e o app passa a preferir as versões novas, caindo
para as atuais quando faltar alguma.
