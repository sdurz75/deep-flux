package org.dual.hexa.core.manual.adapter.out.markdown;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlRenderer;
import org.dual.hexa.core.manual.domain.HeadingSlugs;
import org.dual.hexa.core.manual.domain.ManualHeading;
import org.dual.hexa.core.manual.domain.ManualLinks;
import org.dual.hexa.core.manual.domain.RenderedMarkdown;
import org.dual.hexa.core.manual.port.out.IMarkdownRenderer;
import org.springframework.stereotype.Component;

/**
 * Markdown (CommonMark + tabelle) -> HTML. Qui, e solo qui, ci sono i tipi della libreria. L'HTML grezzo dei sorgenti e' scritto come testo e gli URL con
 * schemi pericolosi ({@code javascript:}) sono scartati: i {@code .md} sono del repo, ma il renderer non si fida lo stesso. Ogni titolo riceve l'{@code id}
 * di {@link HeadingSlugs} e ogni link e' riscritto secondo {@link ManualLinks} (pagine del manuale e path dell'app col prefisso della richiesta, link esterni
 * in una nuova scheda).
 */
@Component
public class CommonmarkRenderer implements IMarkdownRenderer {

    private static final int SUMMARY_MAX = 160;
    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());

    /** {@code Parser} e' thread-safe una volta costruito; l'{@code HtmlRenderer} no, perche' porta lo stato della singola pagina, e si costruisce a ogni chiamata. */
    private final Parser parser = Parser.builder().extensions(EXTENSIONS).includeSourceSpans(IncludeSourceSpans.BLOCKS).build();

    @Override
    public RenderedMarkdown render(String markdown, String linkPrefix) {
        Node document = parser.parse(markdown);
        HeadingCollector collector = new HeadingCollector();
        document.accept(collector);
        String html = HtmlRenderer.builder()
                .extensions(EXTENSIONS)
                .escapeHtml(true)
                .sanitizeUrls(true)
                .attributeProviderFactory(context -> new ManualAttributes(collector.ids, linkPrefix))
                .build()
                .render(document);
        return new RenderedMarkdown(html, List.copyOf(collector.headings), collector.title, summaryOf(document));
    }

    /** Il testo semplice di un nodo: lettere, codice e a capo (come spazio), senza la formattazione. */
    private static String plainText(Node node) {
        StringBuilder text = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override
            public void visit(Text t) {
                text.append(t.getLiteral());
            }

            @Override
            public void visit(Code c) {
                text.append(c.getLiteral());
            }

            @Override
            public void visit(SoftLineBreak b) {
                text.append(' ');
            }

            @Override
            public void visit(HardLineBreak b) {
                text.append(' ');
            }
        });
        return text.toString().strip();
    }

    /** Il primo paragrafo di primo livello del documento (non quelli dentro liste o citazioni), accorciato. */
    private static String summaryOf(Node document) {
        for (Node child = document.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Paragraph) {
                String text = plainText(child);
                return text.length() <= SUMMARY_MAX ? text : text.substring(0, SUMMARY_MAX - 1).stripTrailing() + "…";
            }
        }
        return "";
    }

    /** Raccoglie i titoli in ordine di documento e assegna a ciascuno la sua ancora. */
    private static final class HeadingCollector extends AbstractVisitor {

        private final HeadingSlugs slugs = new HeadingSlugs();
        final Map<Heading, String> ids = new IdentityHashMap<>();
        final List<ManualHeading> headings = new ArrayList<>();
        String title;

        @Override
        public void visit(Heading heading) {
            String text = plainText(heading);
            String id = slugs.next(text);
            ids.put(heading, id);
            List<SourceSpan> spans = heading.getSourceSpans();
            headings.add(new ManualHeading(heading.getLevel(), id, text, spans.isEmpty() ? 0 : spans.get(0).getLineIndex() + 1));
            if (title == null && heading.getLevel() == 1) {
                title = text;
            }
        }
    }

    /** Aggiunge l'{@code id} ai titoli e riscrive gli {@code href}. */
    private static final class ManualAttributes implements AttributeProvider {

        private final Map<Heading, String> ids;
        private final String prefix;

        ManualAttributes(Map<Heading, String> ids, String prefix) {
            this.ids = ids;
            this.prefix = prefix == null ? "" : prefix;
        }

        @Override
        public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
            if (node instanceof Heading heading) {
                String id = ids.get(heading);
                if (id != null) {
                    attributes.put("id", id);
                }
            } else if (node instanceof Link) {
                rewrite(attributes);
            }
        }

        private void rewrite(Map<String, String> attributes) {
            String href = attributes.get("href");
            ManualLinks.Target target = ManualLinks.classify(href);
            switch (target.kind()) {
                case PAGE -> attributes.put("href", prefix + "/manual/" + target.slug() + (target.anchor().isEmpty() ? "" : "#" + target.anchor()));
                case APP -> attributes.put("href", prefix + target.path());
                case EXTERNAL -> {
                    if (href.startsWith("http") || href.startsWith("//")) {
                        attributes.put("target", "_blank");
                        attributes.put("rel", "noopener");
                    }
                }
                default -> {
                }
            }
        }
    }
}
