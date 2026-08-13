"""
Gera os sprites HD da Pokedex nos servidores do GitHub.

Roda sem placa de video e sem nada instalado na maquina de ninguem: o GitHub
Actions cuida de tudo. Por isso a ampliacao aqui nao e por IA — e o algoritmo
Scale2x/Scale3x, feito especificamente para pixel art.

POR QUE SCALE2X E NAO UMA AMPLIACAO COMUM
  Ampliacao comum (bicubica, Lanczos) foi feita para fotografia: ela borra as
  bordas para disfarcar a falta de informacao. Em pixel art isso destroi o
  desenho — foi exatamente o problema original.

  Ampliacao "sem suavizar" mantem tudo nitido, mas os quadrados continuam
  quadrados: e o que o proprio app ja faz de graca na hora de desenhar, entao
  gerar arquivo para isso nao adianta nada.

  O Scale2x olha os quatro vizinhos de cada pixel e so arredonda onde existe
  uma diagonal de verdade no desenho. Linhas retas continuam retas, curvas
  ficam curvas. E o meio-termo que faz diferenca na tela sem inventar detalhe
  que nao existe.

USO
  python gerar-sprites-hd.py --escala 3
  python gerar-sprites-hd.py --parte 1 --de 4     (para dividir em paralelo)
  python gerar-sprites-hd.py --apenas 25,94,6     (teste rapido)
"""

import argparse
import json
import os
import sys
import time

try:
    import numpy as np
except ImportError:
    sys.exit("Falta numpy. Rode:  pip install numpy pillow requests")

try:
    from PIL import Image, ImageSequence
except ImportError:
    sys.exit("Falta Pillow. Rode:  pip install numpy pillow requests")

try:
    import requests
except ImportError:
    sys.exit("Falta requests. Rode:  pip install numpy pillow requests")


FONTE = "https://cdn.jsdelivr.net/gh/PokeAPI/sprites@master/sprites/pokemon/other/showdown/"
ULTIMO_POKEMON = 1025

AQUI = os.path.dirname(os.path.abspath(__file__))
PASTA_SAIDA = os.path.join(AQUI, "output")


# ----------------------------------------------------------------------
# Ampliacao para pixel art
# ----------------------------------------------------------------------

def _vizinhos(imagem):
    """Devolve as quatro bordas de cada pixel, repetindo a borda da imagem."""
    cima = np.vstack([imagem[:1], imagem[:-1]])
    baixo = np.vstack([imagem[1:], imagem[-1:]])
    esquerda = np.hstack([imagem[:, :1], imagem[:, :-1]])
    direita = np.hstack([imagem[:, 1:], imagem[:, -1:]])
    return cima, baixo, esquerda, direita


def _iguais(a, b):
    """Compara pixels RGBA inteiros, nao canal a canal."""
    return np.all(a == b, axis=-1)


def scale2x(imagem):
    """Dobra o tamanho arredondando so onde ha diagonal no desenho."""
    altura, largura = imagem.shape[:2]
    A, D, C, B = _vizinhos(imagem)   # cima, baixo, esquerda, direita
    P = imagem

    e0 = np.where((_iguais(C, A) & ~_iguais(C, D) & ~_iguais(A, B))[..., None], A, P)
    e1 = np.where((_iguais(A, B) & ~_iguais(A, C) & ~_iguais(B, D))[..., None], B, P)
    e2 = np.where((_iguais(D, C) & ~_iguais(D, B) & ~_iguais(C, A))[..., None], C, P)
    e3 = np.where((_iguais(B, D) & ~_iguais(B, A) & ~_iguais(D, C))[..., None], D, P)

    saida = np.empty((altura * 2, largura * 2, imagem.shape[2]), dtype=imagem.dtype)
    saida[0::2, 0::2] = e0
    saida[0::2, 1::2] = e1
    saida[1::2, 0::2] = e2
    saida[1::2, 1::2] = e3
    return saida


def scale3x(imagem):
    """Triplica o tamanho. Mesma ideia do Scale2x, com o centro preservado."""
    altura, largura = imagem.shape[:2]
    A, D, C, B = _vizinhos(imagem)
    P = imagem

    # Diagonais, para os cantos
    AC = np.vstack([C[:1], C[:-1]])          # cima-esquerda
    AB = np.vstack([B[:1], B[:-1]])          # cima-direita
    DC = np.vstack([C[1:], C[-1:]])          # baixo-esquerda
    DB = np.vstack([B[1:], B[-1:]])          # baixo-direita

    ca = _iguais(C, A) & ~_iguais(C, D) & ~_iguais(A, B)
    ab = _iguais(A, B) & ~_iguais(A, C) & ~_iguais(B, D)
    dc = _iguais(D, C) & ~_iguais(D, B) & ~_iguais(C, A)
    bd = _iguais(B, D) & ~_iguais(B, A) & ~_iguais(D, C)

    e = [None] * 9
    e[0] = np.where(ca[..., None], C, P)
    e[1] = np.where((ca & ~_iguais(P, AB) | ab & ~_iguais(P, AC))[..., None], A, P)
    e[2] = np.where(ab[..., None], B, P)
    e[3] = np.where((ca & ~_iguais(P, DC) | dc & ~_iguais(P, AC))[..., None], C, P)
    e[4] = P
    e[5] = np.where((ab & ~_iguais(P, DB) | bd & ~_iguais(P, AB))[..., None], B, P)
    e[6] = np.where(dc[..., None], C, P)
    e[7] = np.where((dc & ~_iguais(P, DB) | bd & ~_iguais(P, DC))[..., None], D, P)
    e[8] = np.where(bd[..., None], B, P)

    saida = np.empty((altura * 3, largura * 3, imagem.shape[2]), dtype=imagem.dtype)
    for indice in range(9):
        saida[indice // 3::3, indice % 3::3] = e[indice]
    return saida


def ampliar(quadro, escala):
    """Combina os passos para chegar na escala pedida."""
    matriz = np.array(quadro.convert("RGBA"))
    if escala == 2:
        matriz = scale2x(matriz)
    elif escala == 3:
        matriz = scale3x(matriz)
    elif escala == 4:
        matriz = scale2x(scale2x(matriz))
    elif escala == 6:
        matriz = scale3x(scale2x(matriz))
    else:
        raise ValueError("escala aceita: 2, 3, 4 ou 6")
    return Image.fromarray(matriz, "RGBA")


# ----------------------------------------------------------------------
# Quadros
# ----------------------------------------------------------------------

def extrair_quadros(caminho):
    """Desmonta o GIF preservando transparencia, duracao e ordem.

    GIF guarda cada quadro como diferenca do anterior. Ler sem compor sobre o
    acumulado deixa buracos e rastros — por isso cada quadro e desenhado sobre
    o anterior, que e o que o navegador faz ao exibir.
    """
    imagem = Image.open(caminho)
    quadros, duracoes = [], []
    acumulado = Image.new("RGBA", imagem.size, (0, 0, 0, 0))

    for quadro in ImageSequence.Iterator(imagem):
        if quadro.info.get("disposal", 0) == 2:
            acumulado = Image.new("RGBA", imagem.size, (0, 0, 0, 0))
        composto = acumulado.copy()
        composto.alpha_composite(quadro.convert("RGBA"))
        quadros.append(composto)
        acumulado = composto
        duracoes.append(max(20, int(quadro.info.get("duration", 60))))

    return quadros, duracoes


# ----------------------------------------------------------------------
# Principal
# ----------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--escala", type=int, default=3)
    parser.add_argument("--qualidade", type=int, default=88)
    parser.add_argument("--parte", type=int, default=1, help="qual pedaco processar")
    parser.add_argument("--de", type=int, default=1, help="em quantos pedacos dividir")
    parser.add_argument("--apenas", default="")
    args = parser.parse_args()

    todos = ([int(x) for x in args.apenas.split(",") if x.strip().isdigit()]
             if args.apenas else list(range(1, ULTIMO_POKEMON + 1)))
    # Divisao intercalada: cada pedaco pega um a cada N, então todos levam
    # mais ou menos o mesmo tempo, mesmo com sprites de tamanhos diferentes.
    ids = todos[args.parte - 1::args.de] if args.de > 1 else todos

    os.makedirs(PASTA_SAIDA, exist_ok=True)
    print(f"Parte {args.parte} de {args.de} — {len(ids)} sprites, escala {args.escala}x", flush=True)

    manifesto, feitos, sem_fonte, falhas = {}, 0, 0, 0
    comeco = time.time()

    for posicao, numero in enumerate(ids, 1):
        destino = os.path.join(PASTA_SAIDA, f"{numero}.webp")
        temporario = os.path.join(PASTA_SAIDA, f"_{numero}.gif")
        try:
            resposta = requests.get(f"{FONTE}{numero}.gif", timeout=30)
            if resposta.status_code != 200 or len(resposta.content) < 200:
                sem_fonte += 1
                continue
            with open(temporario, "wb") as arquivo:
                arquivo.write(resposta.content)

            quadros, duracoes = extrair_quadros(temporario)
            if not quadros:
                raise RuntimeError("nenhum quadro")
            grandes = [ampliar(q, args.escala) for q in quadros]
            grandes[0].save(destino, format="WEBP", save_all=True,
                            append_images=grandes[1:], duration=duracoes,
                            loop=0, quality=args.qualidade, method=6)
            manifesto[str(numero)] = os.path.getsize(destino)
            feitos += 1
        except Exception as erro:
            falhas += 1
            print(f"  #{numero} falhou: {erro}", flush=True)
        finally:
            if os.path.exists(temporario):
                os.remove(temporario)

        if posicao % 20 == 0 or posicao == len(ids):
            print(f"  {posicao}/{len(ids)} | prontos {feitos}", flush=True)

    with open(os.path.join(PASTA_SAIDA, f"manifesto-{args.parte}.json"), "w", encoding="utf-8") as arquivo:
        json.dump(manifesto, arquivo)

    total = sum(manifesto.values()) / 1024 / 1024
    print(f"\nParte {args.parte}: {feitos} gerados, {sem_fonte} sem sprite na fonte, "
          f"{falhas} falhas, {total:.1f} MB, {int(time.time() - comeco)}s")


if __name__ == "__main__":
    main()
