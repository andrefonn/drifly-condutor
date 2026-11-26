package com.drifly.condutor

/** Área do mapa visível agora, em graus decimais. */
data class AreaMapa(
    val norte: Double,
    val sul: Double,
    val leste: Double,
    val oeste: Double
)

/** Motivo de denúncia: o motorista passou e não encontrou o sinal. */
const val MOTIVO_NAO_EXISTE = "nao_existe"

/** Motivo de denúncia: conteúdo impróprio (sai do mapa na hora). */
const val MOTIVO_OFENSIVO = "ofensivo"

/**
 * Quem pode tirar um sinal do mapa: quem criou ([Signal.ehMeu]) e os curadores
 * ([Signal.curador]). O app usa isso só para decidir se mostra o botão
 * "Remover sinal"; quando o servidor entrar, ele confere a mesma regra.
 */
fun podeRemoverSinal(sinal: Signal?, curador: Boolean = false): Boolean {
    if (sinal == null) return false
    return sinal.ehMeu || sinal.curador || curador
}

/**
 * Fonte dos sinais colaborativos mostrados no mapa.
 *
 * Nesta fase o app não tem servidor nem banco remoto. A única implementação
 * ativa é [SinalIndisponivelRepository], que devolve um mapa vazio e honesto —
 * nunca inventa pino de exemplo. Quando o backend do DriFly Condutor entrar,
 * basta criar outra implementação desta interface.
 */
interface SignalRepository {

    /** `true` quando os sinais vêm de um servidor. */
    val online: Boolean

    /** Foto atual dos sinais. */
    fun listar(): List<Signal>

    /** Registra um ouvinte; ele recebe a lista atual imediatamente. */
    fun observar(ouvinte: Ouvinte)

    /** Remove o ouvinte (chamar em onDestroyView do fragmento). */
    fun pararDeObservar(ouvinte: Ouvinte)

    /** Informa a área visível no mapa (usado quando há servidor). */
    fun atualizarAreaVisivel(area: AreaMapa)

    /**
     * Vota num sinal. [distanciaM] é a distância do motorista até o sinal em
     * metros: votar exige estar perto (`Signal.DISTANCIA_VOTO_M`).
     */
    fun votar(
        id: String,
        sim: Boolean,
        distanciaM: Double,
        aoConcluir: (ResultadoVoto) -> Unit
    )

    /** Cria um sinal na posição informada (a posição atual do motorista). */
    fun criar(
        tipo: SignalType,
        lat: Double,
        lng: Double,
        texto: String? = null,
        tipoEvento: String? = null,
        aoConcluir: (ResultadoCriacao) -> Unit
    )

    /** Denúncia de um sinal: não existe mais ([MOTIVO_NAO_EXISTE]) ou ofende. */
    fun denunciar(
        id: String,
        motivo: String,
        aoConcluir: (Boolean, String) -> Unit
    )

    /** Remove um sinal do mapa (o autor desiste do próprio pino). */
    fun remover(
        id: String,
        aoConcluir: (Boolean, String) -> Unit
    )

    /** Callback simples de mudança de lista (sem Flow/LiveData). */
    interface Ouvinte {
        fun aoMudarSinais(sinais: List<Signal>)

        /** Falha de rede/permissão. */
        fun aoFalhar(mensagem: String) {}
    }
}

/** Resultado de uma tentativa de voto. */
data class ResultadoVoto(
    val aceito: Boolean,
    val mensagem: String,
    val removido: Boolean = false
)

/** Resultado de uma tentativa de criar sinal. */
data class ResultadoCriacao(
    val ok: Boolean,
    val mensagem: String
)

/**
 * Posição usada quando o app ainda não tem permissão de localização ou não
 * recebeu nenhuma posição do GPS.
 */
object MapaDemo {
    const val LAT = -26.307222
    const val LNG = -48.845
    const val ZOOM = 13.8f
}

/**
 * Repositório do mapa enquanto não existe servidor no DriFly Condutor.
 *
 * Um mapa vazio e honesto é melhor que um mapa cheio de ficção: sem backend,
 * o mapa avisa que está offline em vez de inventar pinos.
 */
object SinalIndisponivelRepository : SignalRepository {

    /** Mensagem única, usada pelo aviso do mapa e pelos callbacks. */
    const val MENSAGEM = "Mapa colaborativo sem conexão (chega na próxima fase)"

    override val online: Boolean = false

    override fun listar(): List<Signal> = emptyList()

    override fun observar(ouvinte: SignalRepository.Ouvinte) {
        ouvinte.aoMudarSinais(emptyList())
        ouvinte.aoFalhar(MENSAGEM)
    }

    override fun pararDeObservar(ouvinte: SignalRepository.Ouvinte) = Unit

    override fun atualizarAreaVisivel(area: AreaMapa) = Unit

    override fun votar(
        id: String,
        sim: Boolean,
        distanciaM: Double,
        aoConcluir: (ResultadoVoto) -> Unit
    ) {
        aoConcluir(ResultadoVoto(false, MENSAGEM))
    }

    override fun criar(
        tipo: SignalType,
        lat: Double,
        lng: Double,
        texto: String?,
        tipoEvento: String?,
        aoConcluir: (ResultadoCriacao) -> Unit
    ) {
        aoConcluir(ResultadoCriacao(false, MENSAGEM))
    }

    override fun denunciar(
        id: String,
        motivo: String,
        aoConcluir: (Boolean, String) -> Unit
    ) {
        aoConcluir(false, MENSAGEM)
    }

    override fun remover(
        id: String,
        aoConcluir: (Boolean, String) -> Unit
    ) {
        aoConcluir(false, MENSAGEM)
    }
}
