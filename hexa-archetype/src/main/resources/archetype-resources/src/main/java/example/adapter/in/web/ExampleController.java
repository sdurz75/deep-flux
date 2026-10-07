#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.example.adapter.in.web;

import ${package}.example.port.in.IExampleRemoteStatus;
import ${package}.example.port.in.IExamples;
import jakarta.servlet.http.HttpServletResponse;
import org.hexa.core.kernel.i18n.Messages;
import org.hexa.core.kernel.remote.RemoteServiceException;
import org.hexa.core.storage.domain.StorageException;
import org.hexa.core.storage.domain.UploadedFile;
import org.hexa.core.web.HtmxEvents;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * Pagina di esempio: stessa URL, due risposte distinte da {@code HX-Request} (fragment se htmx, pagina intera altrimenti). Mostra anche un upload
 * (il {@code MultipartFile} si converte qui in {@code UploadedFile}: le porte non vedono il framework web) e un evento htmx dal server ({@code HtmxEvents}).
 */
@Controller
public class ExampleController {

    private static final String LIST_FRAGMENT = "fragments/app/example-list :: list(items=${symbol_dollar}{items}, error=${symbol_dollar}{error})";

    private final IExamples examples;
    private final IExampleRemoteStatus remoteStatus;
    private final Messages messages;
    private final HtmxEvents htmxEvents;

    public ExampleController(IExamples examples, IExampleRemoteStatus remoteStatus, Messages messages, HtmxEvents htmxEvents) {
        this.examples = examples;
        this.remoteStatus = remoteStatus;
        this.messages = messages;
        this.htmxEvents = htmxEvents;
    }

    @GetMapping("/example")
    public String page(Model model, @RequestHeader(value = "HX-Request", required = false) String htmx) {
        model.addAttribute("items", examples.list());
        model.addAttribute("error", null);
        return htmx != null ? LIST_FRAGMENT : "app/example";
    }

    @PostMapping("/example")
    public String add(@RequestParam(defaultValue = "") String title, @RequestParam(required = false) MultipartFile attachment, Model model,
                      @RequestHeader(value = "HX-Request", required = false) String htmx) {
        String error = null;
        try {
            examples.add(title, toUploadedFile(attachment));
        } catch (IllegalArgumentException e) {
            error = messages.get("example.error.titleRequired");
        } catch (StorageException e) {
            // Un rifiuto ATTESO (tipo o dimensione non validi) e' solo un messaggio; un guasto vero (disco, WebDAV) risale al resolver del core,
            // che lo registra e risponde 502/500.
            if (e.kind() != RemoteServiceException.Kind.REJECTED) {
                throw e;
            }
            error = e.getMessage();
        }
        if (htmx == null) {
            return "redirect:/example";
        }
        model.addAttribute("items", examples.list());
        model.addAttribute("error", error);
        return LIST_FRAGMENT;
    }

    @PostMapping("/example/{id}/delete")
    public String delete(@PathVariable Long id, Model model, HttpServletResponse response,
                         @RequestHeader(value = "HX-Request", required = false) String htmx) {
        examples.delete(id);
        if (htmx == null) {
            return "redirect:/example";
        }
        // Un evento htmx per il client (qui solo dimostrativo: un componente puo' ascoltare 'example-deleted'; per un toast si usa addToastHeader).
        htmxEvents.addHxTrigger(response, "example-deleted", id);
        model.addAttribute("items", examples.list());
        model.addAttribute("error", null);
        return LIST_FRAGMENT;
    }

    /**
     * Chiamata a un servizio esterno. Nessun {@code try/catch}: se fallisce, l'{@code ExampleRemoteException} risale al resolver del core, che la registra
     * ({@code /system/events}, campanella), la logga e risponde con un toast (502 per i guasti, 500 per un bug, 422 senza registro per un rifiuto atteso);
     * htmx non sostituisce il target su un errore, quindi lo stato precedente resta.
     */
    @PostMapping("/example/remote-check")
    public String remoteCheck(Model model) {
        model.addAttribute("status", remoteStatus.check());
        return "fragments/app/example-remote :: status(status=${symbol_dollar}{status})";
    }

    private static UploadedFile toUploadedFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        return new UploadedFile(file.getOriginalFilename(), file.getSize(), file::getInputStream);
    }
}
