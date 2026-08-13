"""
Processador em lote dos sprites animados da Pokedex.

O QUE ELE FAZ, EM UMA PASSADA
  1. baixa os 1.025 sprites animados originais (pula os que ja existem)
  2. desmonta cada um em quadros, preservando transparencia, duracao e ordem
  3. amplia cada quadro com o Real-ESRGAN, com parametros identicos para toda
     a colecao — e isso que evita o olho do Pokemon mudar de forma de um
     quadro para outro
  4. remonta como WebP animado, que e menor que GIF e a WebView do Android 8+
     le sem problema
  5. escreve um manifesto dizendo quais Pokemon tem versao HD
  6. pode ser interrompido e continuado: o que ja terminou nao e refeito

POR QUE FATOR DE ESCALA E NAO 512x512
  Os sprites nao tem tamanho uniforme. Medindo 18 deles: de 45x49 a 156x127.
  Enquadrar tudo num quadrado fixo deforma os alongados (Chandelure e 131x76)
  e infla os pequenos. O tamanho relativo tambem tem significado — Rayquaza e
  maior que Jigglypuff de proposito. Entao ampliamos todos pelo mesmo fator e
  deixamos a proporcao intacta.

O QUE INSTALAR ANTES
  Python 3.9 ou mais novo  ->  https://www.python.org/downloads/
      Na instalacao, marque "Add Python to PATH".
  Pillow                   ->  pip install pillow requests
  Real-ESRGAN (ncnn)       ->  https://github.com/xinntao/Real-ESRGAN/releases
      Baixe "realesrgan-ncnn-vulkan-...-windows.zip", extraia, e coloque a
      pasta ao lado deste arquivo ou informe o caminho com --esrgan.
      Essa versao roda em GPU Intel/AMD comuns (Vulkan), nao precisa NVIDIA.

COMO RODAR
  python processar-sprites.py
  python processar-sprites.py --escala 4 --qualidade 90
  python processar-sprites.py --apenas 25,94,6        (teste com poucos)
  python processar-sprites.py --sem-ia                (so converte p/ WebP)
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

try:
    from PIL import Image, ImageSequence
except ImportError:
    sys.exit("Falta a biblioteca Pillow. Rode:  pip install pillow requests")

# O nome do redimensionamento sem suavizacao mudou de lugar entre as versoes
# do Pillow. Resolver aqui evita o script quebrar justamente no teste inicial.
try:
    SEM_SUAVIZAR = Image.Resampling.NEAREST
except AttributeError:
    SEM_SUAVIZAR = Image.NEAREST

try:
    import requests
except ImportError:
    sys.exit("Falta a biblioteca requests. Rode:  pip install pillow requests")


FONTE = "https://cdn.jsdelivr.net/gh/PokeAPI/sprites@master/sprites/pokemon/other/showdown/"
ULTIMO_POKEMON = 1025

AQUI = os.path.dirname(os.path.abspath(__file__))
PASTA_ORIGINAIS = os.path.join(AQUI, "SPRITES_ORIGINAIS")
PASTA_SAIDA = os.path.join(AQUI, "output")
MANIFESTO = os.path.join(PASTA_SAIDA, "manifesto.json")


# ----------------------------------------------------------------------
# 1. Baixar os originais
# ----------------------------------------------------------------------

def baixar_originais(ids):
    """Baixa o que falta. Nem todo Pokemon tem sprite animado — cerca de 10%
    da Pokedex nao tem, e isso nao e erro: e limite da fonte."""
    os.makedirs(PASTA_ORIGINAIS, exist_ok=True)
    baixados = existentes = ausentes = 0

    for indice, numero in enumerate(ids, 1):
        destino = os.path.join(PASTA_ORIGINAIS, f"{numero}.gif")
        if os.path.exists(destino) and os.path.getsize(destino) > 200:
            existentes += 1
            continue
        try:
            resposta = requests.get(f"{FONTE}{numero}.gif", timeout=30)
            if resposta.status_code == 200 and len(resposta.content) > 200:
                with open(destino, "wb") as arquivo:
                    arquivo.write(resposta.content)
                baixados += 1
            else:
                ausentes += 1
        except Exception:
            ausentes += 1
        if indice % 25 == 0:
            print(f"  baixando... {indice}/{len(ids)}", flush=True)

    print(f"\nOriginais: {baixados} baixados agora, {existentes} ja existiam, "
          f"{ausentes} sem sprite animado na fonte.")


# ----------------------------------------------------------------------
# 2. Desmontar em quadros
# ----------------------------------------------------------------------

def extrair_quadros(caminho):
    """Devolve (lista de imagens RGBA, lista de duracoes em ms).

    GIF animado guarda cada quadro como uma diferenca do anterior, com regras
    de descarte. Ler quadro a quadro sem compor sobre o anterior produz peda-
    cos faltando e rastros. Aqui cada quadro e composto sobre o acumulado, que
    e o que o navegador faz ao exibir.
    """
    imagem = Image.open(caminho)
    quadros, duracoes = [], []
    acumulado = Image.new("RGBA", imagem.size, (0, 0, 0, 0))

    for quadro in ImageSequence.Iterator(imagem):
        atual = quadro.convert("RGBA")
        descarte = quadro.info.get("disposal", 0)
        if descarte == 2:
            # "restaurar ao fundo": o quadro comeca limpo
            acumulado = Image.new("RGBA", imagem.size, (0, 0, 0, 0))
        composto = acumulado.copy()
        composto.alpha_composite(atual)
        quadros.append(composto)
        acumulado = composto
        duracoes.append(max(20, int(quadro.info.get("duration", 60))))

    return quadros, duracoes


# ----------------------------------------------------------------------
# 3. Ampliar com IA
# ----------------------------------------------------------------------

def localizar_esrgan(informado):
    if informado:
        return informado if os.path.isfile(informado) else None
    nomes = ["realesrgan-ncnn-vulkan.exe", "realesrgan-ncnn-vulkan"]
    for raiz, _, arquivos in os.walk(AQUI):
        for nome in nomes:
            if nome in arquivos:
                return os.path.join(raiz, nome)
    for nome in nomes:
        achado = shutil.which(nome)
        if achado:
            return achado
    return None


def ampliar_quadros(quadros, esrgan, escala, modelo):
    """Amplia todos os quadros de uma vez, com os MESMOS parametros.

    Processar a pasta inteira numa chamada, em vez de um quadro por vez, e o
    que mantem a coerencia entre quadros: o modelo recebe todos com a mesma
    configuracao e nao ha variacao de execucao para execucao.
    """
    with tempfile.TemporaryDirectory() as entrada, tempfile.TemporaryDirectory() as saida:
        for indice, quadro in enumerate(quadros):
            quadro.save(os.path.join(entrada, f"{indice:04d}.png"))

        comando = [esrgan, "-i", entrada, "-o", saida,
                   "-s", str(escala), "-n", modelo, "-f", "png"]
        resultado = subprocess.run(comando, capture_output=True, text=True)
        if resultado.returncode != 0:
            raise RuntimeError((resultado.stderr or "falha no Real-ESRGAN").strip()[:300])

        ampliados = []
        for indice in range(len(quadros)):
            caminho = os.path.join(saida, f"{indice:04d}.png")
            if not os.path.exists(caminho):
                raise RuntimeError(f"quadro {indice} nao saiu do Real-ESRGAN")
            ampliados.append(Image.open(caminho).convert("RGBA"))
        return ampliados


def ampliar_sem_ia(quadros, escala):
    """Ampliacao simples, sem IA: mantem os pixels definidos.
    Serve para testar o resto do processo — o resultado e o mesmo que o app
    ja faz sozinho ao desenhar, entao nao vale como versao final."""
    return [q.resize((q.width * escala, q.height * escala), SEM_SUAVIZAR) for q in quadros]


# ----------------------------------------------------------------------
# 4. Remontar como WebP animado
# ----------------------------------------------------------------------

def salvar_webp(quadros, duracoes, destino, qualidade):
    quadros[0].save(
        destino,
        format="WEBP",
        save_all=True,
        append_images=quadros[1:],
        duration=duracoes,
        loop=0,
        quality=qualidade,
        method=6,          # compressao mais lenta e mais eficiente
        allow_mixed=True,  # deixa o codificador escolher por quadro
    )


# ----------------------------------------------------------------------
# Principal
# ----------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Gera os sprites HD da Pokedex.")
    parser.add_argument("--escala", type=int, default=4, help="fator de ampliacao (padrao 4)")
    parser.add_argument("--qualidade", type=int, default=90, help="qualidade do WebP, 1 a 100")
    parser.add_argument("--modelo", default="realesrgan-x4plus-anime",
                        help="modelo do Real-ESRGAN (o -anime e o certo para sprites)")
    parser.add_argument("--esrgan", default="", help="caminho do realesrgan-ncnn-vulkan")
    parser.add_argument("--apenas", default="", help="processar so estes numeros, separados por virgula")
    parser.add_argument("--sem-ia", action="store_true", help="pular a IA (so testa o processo)")
    parser.add_argument("--sem-baixar", action="store_true", help="usar so o que ja esta na pasta")
    args = parser.parse_args()

    ids = ([int(x) for x in args.apenas.split(",") if x.strip().isdigit()]
           if args.apenas else list(range(1, ULTIMO_POKEMON + 1)))

    print(f"POKECARD — sprites HD\n{len(ids)} Pokemon | escala {args.escala}x | "
          f"WebP qualidade {args.qualidade}\n")

    if not args.sem_baixar:
        print("Passo 1 — baixando os originais")
        baixar_originais(ids)

    esrgan = None
    if not args.sem_ia:
        esrgan = localizar_esrgan(args.esrgan)
        if not esrgan:
            sys.exit(
                "\nNao encontrei o Real-ESRGAN.\n"
                "  Baixe em https://github.com/xinntao/Real-ESRGAN/releases\n"
                "  (arquivo realesrgan-ncnn-vulkan-...-windows.zip), extraia a pasta\n"
                "  aqui do lado, ou informe o caminho com --esrgan.\n"
                "  Para testar o resto sem IA:  python processar-sprites.py --sem-ia\n"
            )
        print(f"\nPasso 2 — ampliando com {os.path.basename(esrgan)} ({args.modelo})")
    else:
        print("\nPasso 2 — ampliando SEM IA (so teste; o app ja faz isso sozinho)")

    os.makedirs(PASTA_SAIDA, exist_ok=True)
    manifesto = {}
    if os.path.exists(MANIFESTO):
        try:
            with open(MANIFESTO, encoding="utf-8") as arquivo:
                manifesto = json.load(arquivo)
        except Exception:
            manifesto = {}

    feitos = pulados = sem_original = falhas = 0
    comeco = time.time()

    for posicao, numero in enumerate(ids, 1):
        origem = os.path.join(PASTA_ORIGINAIS, f"{numero}.gif")
        destino = os.path.join(PASTA_SAIDA, f"{numero}.webp")

        if not os.path.exists(origem):
            sem_original += 1
            continue
        if os.path.exists(destino) and os.path.getsize(destino) > 200:
            manifesto[str(numero)] = os.path.getsize(destino)
            pulados += 1
            continue

        try:
            quadros, duracoes = extrair_quadros(origem)
            if not quadros:
                raise RuntimeError("nenhum quadro lido")
            quadros = (ampliar_sem_ia(quadros, args.escala) if args.sem_ia
                       else ampliar_quadros(quadros, esrgan, args.escala, args.modelo))
            salvar_webp(quadros, duracoes, destino, args.qualidade)
            manifesto[str(numero)] = os.path.getsize(destino)
            feitos += 1
        except Exception as erro:
            falhas += 1
            print(f"  #{numero} falhou: {erro}")

        if posicao % 10 == 0 or posicao == len(ids):
            decorrido = time.time() - comeco
            media = decorrido / max(1, feitos)
            restam = (len(ids) - posicao) * media
            print(f"  {posicao}/{len(ids)} | prontos {feitos} | "
                  f"faltam ~{int(restam // 60)} min", flush=True)
            with open(MANIFESTO, "w", encoding="utf-8") as arquivo:
                json.dump(manifesto, arquivo)

    with open(MANIFESTO, "w", encoding="utf-8") as arquivo:
        json.dump(manifesto, arquivo)

    total_mb = sum(manifesto.values()) / 1024 / 1024
    print(f"\nPronto em {int((time.time() - comeco) // 60)} min")
    print(f"  gerados agora:      {feitos}")
    print(f"  ja existiam:        {pulados}")
    print(f"  sem sprite na fonte:{sem_original}")
    print(f"  falharam:           {falhas}")
    print(f"  total no manifesto: {len(manifesto)} sprites, {total_mb:.1f} MB")
    print(f"\nArquivos em: {PASTA_SAIDA}")
    print("Suba essa pasta num repositorio do GitHub e me diga o nome — eu ligo no app.")


if __name__ == "__main__":
    main()
