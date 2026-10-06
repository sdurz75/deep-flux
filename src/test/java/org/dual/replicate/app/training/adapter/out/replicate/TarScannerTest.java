package org.dual.replicate.app.training.adapter.out.replicate;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Il tar dell'output del trainer ({@code flux-lora/flux-lora.safetensors}, verificato su un training vero): solo byte finti, nessun file. */
class TarScannerTest {

    private static final byte[] WEIGHTS = "SAFETENSORS-CONTENT-OF-NO-PARTICULAR-LENGTH".repeat(30).getBytes(StandardCharsets.UTF_8);

    @Test
    void findsTheWeightsInTheFolderLayoutTheTrainerProduces() throws Exception {
        byte[] tar = new TarBuilder().directory("flux-lora/").file("flux-lora/flux-lora.safetensors", WEIGHTS).build();

        Optional<TarScanner.Entry> entry = TarScanner.find(source(tar), ".safetensors");

        assertThat(entry).hasValueSatisfying(e -> {
            assertThat(e.name()).as("senza cartelle: e' il nome che avra' nel repo").isEqualTo("flux-lora.safetensors");
            assertThat(e.size()).isEqualTo(WEIGHTS.length);
            assertThat(e.dataOffset()).as("directory + intestazione del file").isEqualTo(1024);
        });
    }

    private static TarScanner.Source source(byte[] archive) {
        return (offset, length) -> offset >= archive.length ? new byte[0] : Arrays.copyOfRange(archive, (int) offset, (int) Math.min(archive.length, offset + length));
    }

    private static byte[] slice(byte[] archive, TarScanner.Entry entry) {
        return Arrays.copyOfRange(archive, (int) entry.dataOffset(), (int) (entry.dataOffset() + entry.size()));
    }

    /** Il punto della lettura ad accesso casuale: per trovare i pesi si leggono poche intestazioni, mai l'archivio (qui un file da 50 MB che non si legge). */
    @Test
    void findingTheWeightsReadsOnlyTheHeadersNeverTheContentOfTheFilesBeforeThem() throws Exception {
        byte[] big = new byte[3 * 1024 * 1024];
        byte[] tar = new TarBuilder().file("a/big.bin", big).file("a/lora.safetensors", WEIGHTS).build();
        AtomicLong requested = new AtomicLong();
        TarScanner.Source counting = (offset, length) -> {
            requested.addAndGet(length);
            return source(tar).read(offset, length);
        };

        TarScanner.Entry entry = TarScanner.find(counting, ".safetensors").orElseThrow();

        assertThat(requested.get()).as("due intestazioni, nessun byte del file da 3 MB").isEqualTo(1024);
        assertThat(slice(tar, entry)).isEqualTo(WEIGHTS);
    }

    @Test
    void theContentIsExactlyTheFileAndNothingOfTheArchivePadding() throws Exception {
        byte[] tar = new TarBuilder().directory("flux-lora/").file("flux-lora/flux-lora.safetensors", WEIGHTS).build();
        TarScanner.Entry entry = TarScanner.find(source(tar), ".safetensors").orElseThrow();

        assertThat(slice(tar, entry)).isEqualTo(WEIGHTS);
    }

    @Test
    void skipsEntriesThatAreNotTheWeightsAndKeepsTheOffsetRight() throws Exception {
        byte[] other = "config".repeat(200).getBytes(StandardCharsets.UTF_8);
        byte[] tar = new TarBuilder().file("a/config.json", other).file("a/lora.SAFETENSORS", WEIGHTS).build();

        TarScanner.Entry entry = TarScanner.find(source(tar), ".safetensors").orElseThrow();

        assertThat(entry.name()).isEqualTo("lora.SAFETENSORS");
        assertThat(slice(tar, entry)).isEqualTo(WEIGHTS);
    }

    @Test
    void understandsGnuLongNamesAndUstarPrefixes() throws Exception {
        String longName = "very-long-folder-name-that-needs-the-gnu-extension/and/another/level/weights.safetensors";
        byte[] gnu = new TarBuilder().fileWithLongName(longName, WEIGHTS).build();
        byte[] ustar = new TarBuilder().fileWithPrefix("some/deep/folder", "weights.safetensors", WEIGHTS).build();

        assertThat(TarScanner.find(source(gnu), ".safetensors")).hasValueSatisfying(e -> assertThat(e.name()).isEqualTo("weights.safetensors"));
        assertThat(TarScanner.find(source(ustar), ".safetensors")).hasValueSatisfying(e -> assertThat(e.name()).isEqualTo("weights.safetensors"));
    }

    @Test
    void aDirectoryNamedLikeTheWeightsIsNotTheWeights() throws Exception {
        byte[] tar = new TarBuilder().directory("weights.safetensors/").build();

        assertThat(TarScanner.find(source(tar), ".safetensors")).isEmpty();
    }

    @Test
    void anArchiveWithoutWeightsOrAnEmptyOneIsNotFound() throws Exception {
        byte[] noWeights = new TarBuilder().file("a/readme.txt", "hi".getBytes(StandardCharsets.UTF_8)).build();

        assertThat(TarScanner.find(source(noWeights), ".safetensors")).isEmpty();
        assertThat(TarScanner.find(source(new byte[0]), ".safetensors")).isEmpty();
    }

    /** Un archivio troncato senza pesi dopo l'ultima intestazione leggibile e' "senza pesi" (la fine dei dati e' la fine dell'archivio). */
    @Test
    void aTruncatedArchiveIsSimplyWithoutWeights() throws Exception {
        byte[] truncated = Arrays.copyOf(new TarBuilder().file("a/x.bin", WEIGHTS).build(), 700);

        assertThat(TarScanner.find(source(truncated), ".safetensors")).isEmpty();
    }
}
