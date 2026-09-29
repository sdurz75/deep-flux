// Sorgente UNICA della config Tailwind. Consumata da:
//  - Tailwind CLI (profilo Maven "tailwind"): `module.exports` in Node;
//  - Play CDN (default, fragments/layout.html): caricata come risorsa
//    statica con uno shim `var module = {}` e assegnata a `tailwind.config`.
// Per questo deve restare un singolo `module.exports = {...}` senza require().
// `content` e' ignorato dal Play CDN (scansiona il DOM).
module.exports = {
    content: [
        'src/main/resources/templates/**/*.html',
        'src/main/java/**/*.java',
    ],
    darkMode: ['selector', '[data-theme="dark"]'],
    corePlugins: { preflight: true },
    theme: {
        extend: {
            colors: {
                canvas:            { DEFAULT: '#ffffff', dark: '#0d1117' },
                surface:           { DEFAULT: '#f6f8fa', dark: '#161b22' },
                ink:               { DEFAULT: '#1f2328', dark: '#e6edf3' },
                'ink-muted':       { DEFAULT: '#59636e', dark: '#8b949e' },
                line:              { DEFAULT: '#d0d7de', dark: '#30363d' },
                accent:            { DEFAULT: '#0969da', dark: '#58a6ff' },
                'accent-contrast': { DEFAULT: '#ffffff', dark: '#0d1117' },
                danger:            { DEFAULT: '#cf222e', dark: '#f85149' },
                // scrim/scrim-contrast: overlay del lightbox immagini, sempre
                // nero/bianco a prescindere dal tema (DEFAULT e dark identici).
                scrim:             { DEFAULT: '#000000', dark: '#000000' },
                'scrim-contrast':  { DEFAULT: '#ffffff', dark: '#ffffff' },
            },
            fontFamily: {
                sans: ['-apple-system', 'BlinkMacSystemFont', '"Segoe UI"', 'Roboto', 'Helvetica', 'Arial', 'sans-serif'],
                mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Consolas', 'monospace'],
            },
        },
    },
};
