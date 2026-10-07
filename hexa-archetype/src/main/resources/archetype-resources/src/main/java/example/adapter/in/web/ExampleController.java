#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package}.example.adapter.in.web;

import ${package}.example.port.in.IExamples;
import org.hexa.core.kernel.i18n.Messages;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/** Pagina di esempio: stessa URL, due risposte distinte da {@code HX-Request} (fragment se htmx, pagina intera altrimenti). */
@Controller
public class ExampleController {

    private static final String LIST_FRAGMENT = "fragments/app/example-list :: list(items=${symbol_dollar}{items}, error=${symbol_dollar}{error})";

    private final IExamples examples;
    private final Messages messages;

    public ExampleController(IExamples examples, Messages messages) {
        this.examples = examples;
        this.messages = messages;
    }

    @GetMapping("/example")
    public String page(Model model, @RequestHeader(value = "HX-Request", required = false) String htmx) {
        model.addAttribute("items", examples.list());
        model.addAttribute("error", null);
        return htmx != null ? LIST_FRAGMENT : "app/example";
    }

    @PostMapping("/example")
    public String add(@RequestParam(defaultValue = "") String title, Model model,
                      @RequestHeader(value = "HX-Request", required = false) String htmx) {
        String error = null;
        try {
            examples.add(title);
        } catch (IllegalArgumentException e) {
            error = messages.get("example.error.titleRequired");
        }
        if (htmx == null) {
            return "redirect:/example";
        }
        model.addAttribute("items", examples.list());
        model.addAttribute("error", error);
        return LIST_FRAGMENT;
    }
}
