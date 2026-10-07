// Sorgente UNICA della config Tailwind. Consumata da:
//  - Tailwind CLI (profilo Maven "tailwind"): `module.exports` in Node;
//  - Play CDN (default, fragments/core/layout.html del core): caricata come risorsa statica (/js/tailwind.config.js, vedi pom.xml)
//    con uno shim `var module = {}` e assegnata a `tailwind.config`.
// Per questo deve restare un singolo `module.exports = {...}` senza require(). `content` e' ignorato dal Play CDN (scansiona il DOM).
module.exports = {
    content: [
        'src/main/resources/templates/**/*.html',
        'src/main/java/**/*.java',
        // Template di hexa-core (e hexa-ai) estratti dal profilo "tailwind": le classi che usano vanno scansionate.
        'target/hexa-templates/templates/**/*.html',
    ],
    darkMode: ['selector', '[data-theme="dark"]'],
    corePlugins: { preflight: true },
    theme: {
        extend: {
            // Token { DEFAULT, dark } usati come `bg-canvas dark:bg-canvas-dark`: i primi dieci sono del core (il layout li usa), aggiungi i tuoi sotto.
            colors: {
                canvas:            { DEFAULT: '#ffffff', dark: '#0d1117' },
                surface:           { DEFAULT: '#f6f8fa', dark: '#161b22' },
                ink:               { DEFAULT: '#1f2328', dark: '#e6edf3' },
                'ink-muted':       { DEFAULT: '#59636e', dark: '#8b949e' },
                line:              { DEFAULT: '#d0d7de', dark: '#30363d' },
                accent:            { DEFAULT: '#0969da', dark: '#58a6ff' },
                'accent-contrast': { DEFAULT: '#ffffff', dark: '#0d1117' },
                danger:            { DEFAULT: '#cf222e', dark: '#f85149' },
                warning:           { DEFAULT: '#9a6700', dark: '#d29922' },
                // favourite: usato dai fragment del kit UI (stella dei preferiti nei tag-chips).
                favourite:         { DEFAULT: '#ec4899', dark: '#f472b6' },
                // scrim/scrim-contrast: overlay (lightbox, scrim della sidebar mobile), sempre nero/bianco a prescindere dal tema.
                scrim:             { DEFAULT: '#000000', dark: '#000000' },
                'scrim-contrast':  { DEFAULT: '#ffffff', dark: '#ffffff' },
            },
            // busy-slide: barra indeterminata dell'overlay "operazione in corso" (fragments/core/busy-overlay.html).
            keyframes: {
                'busy-slide': {
                    '0%': { transform: 'translateX(-100%)' },
                    '100%': { transform: 'translateX(300%)' },
                },
            },
            animation: {
                'busy-slide': 'busy-slide 1.4s ease-in-out infinite',
            },
            fontFamily: {
                sans: ['-apple-system', 'BlinkMacSystemFont', '"Segoe UI"', 'Roboto', 'Helvetica', 'Arial', 'sans-serif'],
                mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Consolas', 'monospace'],
            },
        },
    },
};
