package com.hsh.meupdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.LruCache
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Pixmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/** O MuPDF não é thread-safe: todo acesso a ele passa por esta única thread (com pilha grande). */
object Trabalho {
    val mupdf: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "mupdf", 32L * 1024 * 1024).apply { isDaemon = true }
    }.asCoroutineDispatcher()
}

/** Resultado de busca: posição como fração da página (0..1). */
data class Resultado(val pagina: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/** Um documento aberto (PDF, EPUB, CBZ ou imagem) que sabe desenhar suas páginas. */
abstract class Fonte {
    abstract val paginas: Int
    abstract fun tamanho(i: Int): Pair<Float, Float>
    protected abstract fun desenhar(i: Int, larguraPx: Int): Bitmap
    open val despachante: CoroutineDispatcher get() = Trabalho.mupdf
    open val permiteBusca: Boolean get() = false
    open suspend fun buscar(texto: String): List<Resultado> = emptyList()
    abstract val descricaoBase: String
    /** Se o arquivo veio de um URI, guardamos o URI (não uma cópia): é assim que as edições são restauradas depois. */
    var uriOrigem: String? = null
    var nomeOrigem: String? = null
    val descricao: String get() = uriOrigem?.let { "uri|$it|${nomeOrigem ?: ""}" } ?: descricaoBase
    /** Tarefas de limpeza (apagar link/pasta temporária, fechar descritor) executadas ao fechar. */
    val limpezas = ArrayList<() -> Unit>()
    protected abstract fun fecharInterno()

    fun fechar() {
        try { fecharInterno() } catch (e: Exception) { }
        for (l in limpezas) {
            try { l() } catch (e: Exception) { }
        }
        limpezas.clear()
    }

    private val bordas = HashMap<Int, Int>()

    /** Cor predominante nas bordas da página (fundo "Auto" do modo leitura). */
    suspend fun corBorda(i: Int): Int {
        bordas[i]?.let { return it }
        val b = renderizar(i, 64)
        val w = b.width
        val h = b.height
        val esp = maxOf(1, (minOf(w, h) * 0.04f).toInt())
        val cont = HashMap<Int, IntArray>()
        fun amostra(x: Int, y: Int) {
            val p = b.getPixel(x, y)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val bl = p and 0xFF
            val k = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (bl shr 4)
            val a = cont.getOrPut(k) { IntArray(4) }
            a[0]++; a[1] += r; a[2] += g; a[3] += bl
        }
        for (y in 0 until h) {
            if (y < esp || y >= h - esp) {
                for (x in 0 until w) amostra(x, y)
            } else {
                for (x in 0 until esp) amostra(x, y)
                for (x in w - esp until w) amostra(x, y)
            }
        }
        val m = cont.values.maxByOrNull { it[0] } ?: return 0xFF000000.toInt()
        val cor = (0xFF shl 24) or ((m[1] / m[0]) shl 16) or ((m[2] / m[0]) shl 8) or (m[3] / m[0])
        bordas[i] = cor
        return cor
    }

    private val cache = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 1024) / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Desenha a página com a largura pedida (arredondada para múltiplos de 64 px, para aproveitar o cache). */
    suspend fun renderizar(i: Int, larguraPx: Int): Bitmap {
        val q = ((larguraPx + 63) / 64) * 64
        val chave = "$i@$q"
        cache.get(chave)?.let { return it }
        val bmp = withContext(despachante) { desenhar(i, q) }
        cache.put(chave, bmp)
        return bmp
    }
}

/** PDF, EPUB, XPS e outros formatos que o MuPDF abre. */
class FontePdf(private val caminho: String) : Fonte() {
    override val descricaoBase: String get() = "pdf|$caminho"
    private val doc: Document = Document.openDocument(caminho)
    private val tam: List<Pair<Float, Float>>
    override val paginas: Int

    init {
        if (doc.isReflowable()) doc.layout(480f, 720f, 11f)
        paginas = doc.countPages()
        val l = ArrayList<Pair<Float, Float>>(paginas)
        for (i in 0 until paginas) {
            val p = doc.loadPage(i)
            val b = p.getBounds()
            l.add(Pair(maxOf(1f, b.x1 - b.x0), maxOf(1f, b.y1 - b.y0)))
            p.destroy()
        }
        tam = l
    }

    override fun tamanho(i: Int) = tam[i]
    override val permiteBusca: Boolean get() = true

    override fun desenhar(i: Int, larguraPx: Int): Bitmap {
        val pagina = doc.loadPage(i)
        try {
            val b = pagina.getBounds()
            val s = larguraPx / (b.x1 - b.x0)
            val ctm = Matrix(s, 0f, 0f, s, -b.x0 * s, -b.y0 * s)
            val pix = pagina.toPixmap(ctm, ColorSpace.DeviceRGB, false, true)
            try {
                return paraBitmap(pix)
            } finally {
                pix.destroy()
            }
        } finally {
            pagina.destroy()
        }
    }

    private fun paraBitmap(pix: Pixmap): Bitmap {
        val w = pix.getWidth()
        val h = pix.getHeight()
        val n = pix.getNumberOfComponents()
        val stride = pix.getStride()
        val s = pix.getSamples()
        val px = IntArray(w * h)
        var o = 0
        for (y in 0 until h) {
            var p = y * stride
            for (x in 0 until w) {
                val r = s[p].toInt() and 0xFF
                val g = s[p + 1].toInt() and 0xFF
                val bl = s[p + 2].toInt() and 0xFF
                px[o++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                p += n
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    override suspend fun buscar(texto: String): List<Resultado> = withContext(Trabalho.mupdf) {
        val saida = ArrayList<Resultado>()
        for (i in 0 until paginas) {
            val p = doc.loadPage(i)
            try {
                val b = p.getBounds()
                val w = maxOf(1f, b.x1 - b.x0)
                val h = maxOf(1f, b.y1 - b.y0)
                val achados = p.search(texto)
                if (achados != null) {
                    for (grupo in achados) {
                        for (q in grupo) {
                            val xs = floatArrayOf(q.ul_x, q.ur_x, q.ll_x, q.lr_x)
                            val ys = floatArrayOf(q.ul_y, q.ur_y, q.ll_y, q.lr_y)
                            saida.add(Resultado(i, (xs.min() - b.x0) / w, (ys.min() - b.y0) / h,
                                (xs.max() - b.x0) / w, (ys.max() - b.y0) / h))
                        }
                    }
                }
            } finally {
                p.destroy()
            }
            if (saida.size > 3000) break
        }
        saida
    }

    override fun fecharInterno() {
        try { doc.destroy() } catch (e: Exception) { }
    }
}

/** CBZ (ZIP de imagens) e imagens soltas. Usa o decodificador do Android, que lê WebP sem inflar. */
class FonteImagens private constructor(
    private val entradas: List<Entrada>,
    private val zip: ZipFile?,
    override val descricaoBase: String
) : Fonte() {
    class Entrada(val nome: String, val abrir: () -> InputStream)

    private val tam: List<Pair<Float, Float>> = entradas.map { e ->
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        e.abrir().use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth > 0 && o.outHeight > 0) Pair(o.outWidth.toFloat(), o.outHeight.toFloat()) else Pair(1f, 1.4f)
    }
    override val paginas: Int get() = entradas.size
    override val despachante: CoroutineDispatcher get() = Dispatchers.Default

    override fun tamanho(i: Int) = tam[i]

    override fun desenhar(i: Int, larguraPx: Int): Bitmap {
        val w = tam[i].first
        var amostra = 1
        while (w / (amostra * 2) >= larguraPx) amostra *= 2
        val o = BitmapFactory.Options().apply {
            inSampleSize = amostra
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return entradas[i].abrir().use { BitmapFactory.decodeStream(it, null, o) }
            ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    override fun fecharInterno() {
        try { zip?.close() } catch (e: Exception) { }
    }

    companion object {
        private val extensoes = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

        fun deZip(arq: File): FonteImagens {
            val z = ZipFile(arq)
            val nomes = z.entries().asSequence()
                .filter { !it.isDirectory }
                .map { it.name }
                .filter { n ->
                    val base = n.substringAfterLast('/')
                    n.substringAfterLast('.', "").lowercase() in extensoes && !base.startsWith(".") && !n.contains("__MACOSX")
                }
                .sortedWith { a, b -> comparaNatural(a, b) }
                .toList()
            require(nomes.isNotEmpty()) { "Não encontrei imagens dentro do arquivo." }
            return FonteImagens(nomes.map { n -> Entrada(n) { z.getInputStream(z.getEntry(n)) } }, z, "zip|${arq.absolutePath}")
        }

        fun deArquivo(arq: File): FonteImagens =
            FonteImagens(listOf(Entrada(arq.name) { arq.inputStream() }), null, "img|${arq.absolutePath}")

        /** Pasta de imagens (usada também para CBR já extraído). */
        fun dePasta(dir: File): FonteImagens {
            val arqs = (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.extension.lowercase() in extensoes }
                .sortedWith { a, b -> comparaNatural(a.name, b.name) }
            require(arqs.isNotEmpty()) { "Não encontrei imagens dentro do arquivo." }
            return FonteImagens(arqs.map { f -> Entrada(f.name) { f.inputStream() } }, null, "pasta|${dir.absolutePath}")
        }

        private fun nomeCabecalho(h: Any): String {
            for (m in listOf("getFileName", "getFileNameString", "getFileNameW")) {
                try { return (h.javaClass.getMethod(m).invoke(h) as String).replace('\\', '/') } catch (e: Exception) { }
            }
            return "pagina"
        }

        /** CBR (RAR): extrai as imagens para uma pasta com a biblioteca junrar e abre como pasta. */
        fun deRar(arq: File): FonteImagens {
            val dir = File(arq.parentFile, arq.name + "_rar")
            if (dir.exists() && (dir.listFiles()?.isNotEmpty() == true)) return pastaTemporaria(dir)
            dir.mkdirs()
            val classe = try { Class.forName("com.github.junrar.Archive") } catch (e: Exception) {
                throw IllegalStateException("Biblioteca de RAR não encontrada no app.")
            }
            val arch = classe.getConstructor(File::class.java).newInstance(arq)
            try {
                val cabs = (classe.getMethod("getFileHeaders").invoke(arch) as List<*>).filterNotNull()
                val validos = cabs.filter { h ->
                    val dirFlag = try { h.javaClass.getMethod("isDirectory").invoke(h) as Boolean } catch (e: Exception) { false }
                    !dirFlag && nomeCabecalho(h).substringAfterLast('.', "").lowercase() in extensoes
                }
                val ordem = validos.map { nomeCabecalho(it) }.sortedWith { a, b -> comparaNatural(a, b) }
                val posicao = HashMap<String, Int>()
                ordem.forEachIndexed { i, n -> if (!posicao.containsKey(n)) posicao[n] = i }
                val extrair = classe.methods.first { it.name == "extractFile" && it.parameterCount == 2 }
                for (h in validos) {  // na ordem do arquivo (RAR "sólido" exige leitura em sequência)
                    val nome = nomeCabecalho(h)
                    val destino = File(dir, "%05d_%s".format(posicao[nome] ?: 0, nome.substringAfterLast('/')))
                    destino.outputStream().use { os -> extrair.invoke(arch, h, os) }
                }
            } finally {
                try { classe.getMethod("close").invoke(arch) } catch (e: Exception) { }
            }
            return pastaTemporaria(dir)
        }

        /** Abre a pasta de imagens e a apaga quando o documento for fechado (não deixa lixo no armazenamento). */
        private fun pastaTemporaria(dir: File): FonteImagens {
            val f = dePasta(dir)
            f.limpezas.add { dir.deleteRecursively() }
            return f
        }
    }
}

/** Ordenação natural: 2.jpg vem antes de 10.jpg. */
fun comparaNatural(a: String, b: String): Int {
    val re = Regex("(\\d+)|(\\D+)")
    val pa = re.findAll(a.lowercase()).map { it.value }.toList()
    val pb = re.findAll(b.lowercase()).map { it.value }.toList()
    for (k in 0 until minOf(pa.size, pb.size)) {
        val x = pa[k]
        val y = pb[k]
        val nx = x.toLongOrNull()
        val ny = y.toLongOrNull()
        val c = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
        if (c != 0) return c
    }
    return pa.size.compareTo(pb.size)
}

/** Arquivo pronto para ler + como limpar depois (apagar o link, fechar o descritor ou apagar a cópia temporária). */
class Origem(val arquivo: File, val nome: String, val limpeza: () -> Unit)

object Abertura {
    private val mupdfExts = setOf("pdf", "epub", "xps", "oxps", "fb2", "mobi")
    private val imagemExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    fun nomeDe(ctx: Context, uri: Uri): String {
        var nome = "documento"
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) nome = it.getString(0) ?: nome
        }
        return nome
    }

    /**
     * Abre o arquivo SEM copiá-lo: cria um link para o descritor do arquivo original (nada ocupa espaço).
     * Se o provedor não permitir (arquivo "em fluxo", como alguns da nuvem), usa uma cópia TEMPORÁRIA no cache,
     * apagada ao fechar o documento.
     */
    suspend fun abrirOrigem(ctx: Context, uri: Uri): Origem = withContext(Dispatchers.IO) {
        val nome = nomeDe(ctx, uri)
        val seguro = nome.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val base = System.nanoTime()
        try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                var seekavel = true
                try {
                    Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_CUR)
                } catch (e: ErrnoException) {
                    seekavel = false
                }
                if (seekavel) {
                    val pasta = File(ctx.cacheDir, "links").apply { mkdirs() }
                    val link = File(pasta, "${base}_$seguro")
                    try {
                        Os.symlink("/proc/self/fd/${pfd.fd}", link.absolutePath)
                        if (link.canRead() && link.length() > 0L) {
                            return@withContext Origem(link, nome) {
                                try { link.delete() } catch (e: Exception) { }
                                try { pfd.close() } catch (e: Exception) { }
                            }
                        }
                    } catch (e: Exception) { }
                    try { link.delete() } catch (e: Exception) { }
                }
                pfd.close()
            }
        } catch (e: Exception) { }
        val pasta = File(ctx.cacheDir, "temp").apply { mkdirs() }
        val destino = File(pasta, "${base}_$seguro")
        val entrada = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Não consegui ler o arquivo.")
        entrada.use { e -> destino.outputStream().use { o -> e.copyTo(o, 1 shl 16) } }
        Origem(destino, nome) { try { destino.delete() } catch (e: Exception) { } }
    }

    suspend fun abrirArquivo(arquivo: File, nome: String): Fonte = withContext(Dispatchers.IO) {
        val ext = nome.substringAfterLast('.', "").lowercase()
        when {
            ext in mupdfExts -> withContext(Trabalho.mupdf) { FontePdf(arquivo.absolutePath) }
            ext == "cbz" || ext == "zip" -> FonteImagens.deZip(arquivo)
            ext in imagemExts -> FonteImagens.deArquivo(arquivo)
            ext == "cbr" || ext == "rar" -> FonteImagens.deRar(arquivo)
            ext == "cb7" || ext == "7z" -> throw IllegalStateException("CB7/7z ainda não é suportado. Use CBZ ou CBR.")
            else -> withContext(Trabalho.mupdf) { FontePdf(arquivo.absolutePath) }
        }
    }

    /** Abre o documento a partir do URI escolhido (sem deixar cópia no app). */
    suspend fun abrirFonte(ctx: Context, uri: Uri): Fonte {
        val o = abrirOrigem(ctx, uri)
        try {
            val f = abrirArquivo(o.arquivo, o.nome)
            f.limpezas.add(o.limpeza)
            f.uriOrigem = uri.toString()
            f.nomeOrigem = o.nome
            return f
        } catch (e: Throwable) {
            o.limpeza()
            throw e
        }
    }

    /** Reabre uma fonte guardada no salvamento automático. */
    suspend fun reabrir(ctx: Context, descricao: String): Fonte {
        val tipo = descricao.substringBefore('|')
        val resto = descricao.substringAfter('|')
        if (tipo == "uri") return abrirFonte(ctx, Uri.parse(resto.substringBeforeLast('|')))
        throw IllegalStateException("Formato de salvamento antigo: o arquivo original não está mais disponível.")
    }

    suspend fun abrir(ctx: Context, uri: Uri): Projeto {
        val f = abrirFonte(ctx, uri)
        return Projeto.deFonte(f.nomeOrigem ?: "documento", uri.toString(), f)
    }

    /** Projeto novo com uma página por imagem escolhida (para virar PDF/CBZ/EPUB). */
    suspend fun deImagens(ctx: Context, uris: List<Uri>): Projeto {
        val p = Projeto("Imagens.pdf", null)
        val paginas = ArrayList<Pagina>()
        for (u in uris) {
            val f = abrirFonte(ctx, u)
            p.fontes.add(f)
            val (w, h) = f.tamanho(0)
            paginas.add(Pagina(p.novoId(), f, 0, 0, w, h, emptyList()))
        }
        p.estado = Estado(paginas)
        return p
    }

    fun novoEmBranco(): Projeto {
        val p = Projeto("Novo.pdf", null)
        p.estado = Estado(listOf(Pagina(p.novoId(), null, 0, 0, 595f, 842f, emptyList())))
        return p
    }

    // ---------------- armazenamento ----------------
    private fun tamanho(f: File): Long {
        if (!f.exists()) return 0L
        if (f.isFile) return f.length()
        return (f.listFiles() ?: emptyArray()).sumOf { tamanho(it) }
    }

    /** (cache temporário, edições salvas) em bytes. */
    fun tamanhos(ctx: Context): Pair<Long, Long> {
        val cache = tamanho(ctx.cacheDir)
        val edicoes = tamanho(File(ctx.filesDir, "projetos")) + tamanho(File(ctx.filesDir, "itens"))
        return Pair(cache, edicoes)
    }

    /** Apaga cópias e pastas temporárias (inclusive as cópias antigas da versão 0.2). Só chamar sem documentos abertos. */
    fun limparTudo(ctx: Context) {
        for (nome in listOf("trabalho")) File(ctx.filesDir, nome).deleteRecursively()
        for (nome in listOf("links", "temp", "abertos")) File(ctx.cacheDir, nome).deleteRecursively()
    }

    fun apagarEdicoes(ctx: Context) {
        File(ctx.filesDir, "projetos").deleteRecursively()
        File(ctx.filesDir, "itens").deleteRecursively()
    }
}
