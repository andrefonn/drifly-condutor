package com.drifly.condutor

import java.text.Normalizer
import java.util.Locale

/**
 * Camada 1 do filtro das dicas (dentro do app).
 *
 * É o porte fiel da função `badText` do protótipo (`drifly-redesign.html`):
 * - tira acentos e coloca em minúsculas;
 * - troca números/símbolos por letras (0→o, 1→i, 3→e, 4→a, 5→s, 7→t, @→a, $→s);
 * - junta letras separadas por espaço/ponto/hífen/asterisco ("p.u.t.a", "p u t a");
 * - reduz letras repetidas ("puuuta" → "puta");
 * - compara **palavra inteira** contra a lista, então "reputação" e "Paulo" passam;
 * - recusa links, e-mails, telefones e placas de veículo.
 *
 * A camada 2 (Cloud Function com a lista vinda de `config/blocklist`) repete a
 * checagem no servidor, porque esta camada sozinha pode ser burlada.
 */
object ModerationFilter {

    const val MIN_LEN = 8
    const val MAX_LEN = 80

    const val MSG_CURTA = "Escreva uma dica com pelo menos 8 caracteres."
    const val MSG_LONGA = "A dica pode ter no máximo 80 caracteres."
    const val MSG_CONTATO = "Não use links, e-mails nem telefones."
    const val MSG_PLACA = "Não escreva placas de veículo na dica."
    const val MSG_PALAVRA = "Sua dica tem palavras não permitidas. Reescreva de forma respeitosa."

    /** Lista baseada na do protótipo + variações comuns do português brasileiro. */
    private val PALAVRAS_BLOQUEADAS = listOf(
        "porra", "caralho", "puta", "puto", "putaria", "putaco",
        "merda", "bosta", "foda", "foder", "fodase", "fodido", "fdp", "vtnc", "pqp", "krl",
        "buceta", "xoxota", "piroca", "pinto", "pau", "cu", "cuzao",
        "viado", "veado", "bicha", "arrombado", "arrombada",
        "otario", "otaria", "babaca", "idiota", "imbecil", "cacete", "corno",
        "retardado", "escroto", "vagabunda", "vagabundo", "prostituta",
        "golpista", "ladrao", "ladra", "traficante"
    )

    // Sequência de letras soltas ("p.u.t.a", "p u t a"). É avaliada separada das
    // palavras normais porque a sequência pode começar num artigo de uma letra
    // ("o p.u.t.a" vira "oputa"), então os sufixos também são comparados.
    private val SOLTAS = Regex("\\b(?:[a-z][\\s._\\-*]+){2,}[a-z]\\b")
    private val SEPARADORES = Regex("[\\s._\\-*]")
    private val NAO_LETRA = Regex("[^a-z]+")
    private val LETRA_REPETIDA = Regex("(.)\\1+")
    private val ACENTOS = Regex("\\p{M}+")

    // Contato: o mesmo critério do protótipo (link, domínio ou @) e telefones.
    private val CONTATO = Regex("(https?:|www\\.|\\.com|\\.br\\b|@)")
    private val TELEFONE = Regex("\\d[\\s.\\-]?\\d{3,}[\\s.\\-]?\\d{3,}")

    // Placas: Mercosul (ABC1D23) e modelo antigo (ABC-1234 / ABC 1234).
    private val PLACA_MERCOSUL = Regex("\\b[A-Z]{3}[0-9][A-Z][0-9]{2}\\b")
    private val PLACA_ANTIGA = Regex("\\b[A-Z]{3}-?[0-9]{4}\\b")

    /**
     * Lista já normalizada: a comparação acontece sempre no mesmo formato do texto.
     *
     * A ordem importa: precisa vir DEPOIS dos Regex acima, porque `normalizar`
     * os usa durante a inicialização do objeto.
     */
    private val LISTA_NORMALIZADA: Set<String> =
        PALAVRAS_BLOQUEADAS.map { enxugar(normalizar(it)) }.toHashSet()

    /** `null` quando a dica pode ser publicada; caso contrário, a mensagem para o motorista. */
    fun check(text: String): String? {
        val limpo = text.trim()

        if (limpo.length < MIN_LEN) return MSG_CURTA
        if (limpo.length > MAX_LEN) return MSG_LONGA

        val minusculo = limpo.lowercase(Locale.ROOT)
        if (CONTATO.containsMatchIn(minusculo) || TELEFONE.containsMatchIn(limpo)) return MSG_CONTATO

        val maiusculo = limpo.uppercase(Locale.ROOT)
        if (PLACA_MERCOSUL.containsMatchIn(maiusculo) || PLACA_ANTIGA.containsMatchIn(maiusculo)) {
            return MSG_PLACA
        }

        val normalizado = normalizar(limpo)
        val palavras = normalizado.split(NAO_LETRA).filter { it.isNotEmpty() }
        if (palavras.any { LISTA_NORMALIZADA.contains(enxugar(it)) }) return MSG_PALAVRA

        // Letras soltas: junta a sequência e olha todos os sufixos, senão
        // "o p.u.t.a" seria lido como a palavra "oputa" e passaria.
        for (trecho in SOLTAS.findAll(normalizado)) {
            val juntas = trecho.value.replace(SEPARADORES, "")
            for (inicio in 0..(juntas.length - 3)) {
                if (LISTA_NORMALIZADA.contains(enxugar(juntas.substring(inicio)))) {
                    return MSG_PALAVRA
                }
            }
        }

        return null
    }

    fun isAllowed(text: String): Boolean = check(text) == null

    /**
     * Prepara o texto para comparação: sem acento, sem número "fantasiado" de
     * letra e com as letras separadas novamente grudadas.
     */
    private fun normalizar(raw: String): String {
        var texto = Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        texto = texto.replace(ACENTOS, "")

        val trocado = StringBuilder(texto.length)
        for (caractere in texto) {
            trocado.append(
                when (caractere) {
                    '0' -> 'o'
                    '1', '!', '|' -> 'i'
                    '3' -> 'e'
                    '4', '@' -> 'a'
                    '5', '$' -> 's'
                    '7' -> 't'
                    else -> caractere
                }
            )
        }

        return trocado.toString()
    }

    /** "puuuta" e "puta" caem na mesma chave. */
    private fun enxugar(palavra: String): String = palavra.replace(LETRA_REPETIDA, "$1")
}
