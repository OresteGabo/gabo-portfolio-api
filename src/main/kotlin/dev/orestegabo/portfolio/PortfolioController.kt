package dev.orestegabo.portfolio

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

@RestController
@RequestMapping("/api/bonds")
class PortfolioController(private val portfolio: PortfolioService,private val documents: DocumentService,private val statements: StatementService) {
    @GetMapping("/purchases") fun bonds()=mapOf("purchases" to portfolio.bonds(currentAccount().id))
    @GetMapping("/purchases/{id}") fun bond(@PathVariable id: String): Record {
        checkApi(uuid(id),"Invalid purchase id.")
        return mapOf("purchase" to (portfolio.bond(currentAccount().id,id) ?: throw ApiError(404,"Purchase not found.")))
    }
    @PostMapping("/purchases") @ResponseStatus(HttpStatus.CREATED)
    fun createBond(@RequestBody body: Record)=mapOf("purchase" to portfolio.saveBond(currentAccount().id,body))
    @PutMapping("/purchases/{id}") fun updateBond(@PathVariable id: String,@RequestBody body: Record)=mapOf("purchase" to portfolio.saveBond(currentAccount().id,body,id))
    @PostMapping("/purchases/{id}/confirm") fun confirmBond(@PathVariable id: String,@RequestBody body: Record)=mapOf("purchase" to portfolio.confirmPending(currentAccount().id,id,body))
    @DeleteMapping("/purchases/{id}") fun deleteBond(@PathVariable id: String): Record {
        checkApi(portfolio.deleteBond(currentAccount().id,id),"Purchase not found.",404)
        return mapOf("deleted" to true)
    }
    @GetMapping("/equities") fun shares()=mapOf("holdings" to portfolio.shares(currentAccount().id))
    @PostMapping("/equities") @ResponseStatus(HttpStatus.CREATED)
    fun createShare(@RequestBody body: Record)=mapOf("holding" to portfolio.saveShare(currentAccount().id,body))
    @GetMapping("/mobile-overview") fun overview()=currentAccount().let { MobileOverview.create(portfolio.bonds(it.id),portfolio.shares(it.id)) }
    @GetMapping("/documents") fun documents()=mapOf("documents" to documents.documents(currentAccount().id))
    @PostMapping("/documents") fun upload(@RequestBody body: Record)=documents.upload(currentAccount().id,body)
    @GetMapping("/documents/{id}") fun download(@PathVariable id: String): ResponseEntity<ByteArray> {
        val (bytes,name)=documents.download(currentAccount().id,id) ?: throw ApiError(404,"Document not found.")
        val safe=name.replace(Regex("[^a-zA-Z0-9._ -]"),"_")
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"$safe\"").header("X-Content-Type-Options","nosniff").body(bytes)
    }
    @GetMapping("/statement-items") fun missing(@RequestParam(required=false) documentId: String?,@RequestParam(defaultValue="false") all: Boolean)=mapOf("items" to if(documentId!=null || all)statements.records(currentAccount().id,documentId) else statements.missing(currentAccount().id))
    @PostMapping("/statement-items") fun statement(@RequestBody body: Record)=mapOf("rows" to statements.reconcile(currentAccount().id,body))

    @PostMapping("/statement-items/{id}/bond") fun createStatementBond(@PathVariable id: String,@RequestBody body: Record)=mapOf("rows" to statements.createBond(currentAccount().id,id,body))

    @PostMapping("/statement-items/{id}/resolve") fun resolveStatement(@PathVariable id: String,@RequestBody body: Record)=mapOf("rows" to statements.resolve(currentAccount().id,id,body))

    private val reports=ConcurrentHashMap<String,Long>()
    private val log=LoggerFactory.getLogger(javaClass)
    @PostMapping("/import-diagnostics") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun diagnostic(@RequestBody p: Record) {
        val account=currentAccount()
        val allowed=mapOf("stage" to "metadata,file-read,file-envelope,import-load,parser-load,document-open,document-policy,page-open,page-policy,page-text,ocr-load,ocr-render,ocr-read,page-cleanup,purchase-detection", "reason" to "validation,file-unavailable,missing-map-method,missing-browser-api,worker-version,worker-load,syntax,password,cancelled,unknown", "code" to "file_type,file_size,invalid_pdf,unreadable,password,unsafe_content,page_limit,scan_limit,text_limit,not_bond_result,cancelled", "platform" to "ios,android,other")
        checkApi(p.keys==allowed.keys+"version" && p.number("version")==1.0 && allowed.all { (key,values)->p.text(key) in values.split(',') },"Invalid diagnostic report.")
        val now=System.currentTimeMillis()
        if((reports[account.id] ?: 0)+30000<=now) {
            if(reports.size>=512) reports.clear()
            reports[account.id]=now
            log.warn("PDF import diagnostic: stage={}, reason={}, code={}, platform={}",p["stage"],p["reason"],p["code"],p["platform"])
        }
    }
}
