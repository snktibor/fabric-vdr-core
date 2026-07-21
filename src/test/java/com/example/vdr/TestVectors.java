package com.example.vdr;

import java.util.Map;

/**
 * Genuine BLS12-381 test vectors shared by unit and end-to-end fixtures.
 *
 * <p>Sources: {@code G1_GENERATOR} and {@code G2_GENERATOR} are the in-source serde test vectors of
 * {@code blstrs_plus} 0.8.18 ({@code src/serde_impl.rs}), the crypto backend pinned by the
 * AnonCreds v2 reference implementation (credx 0.2.1). The G1 multiples were derived from the
 * generator with textbook affine arithmetic over the BLS12-381 base field and self-checked by
 * reproducing the published generator vector; they are genuine on-curve points in canonical ZCash
 * compressed form (48 bytes, lowercase hex), not fabricated strings.
 */
public final class TestVectors {

    /** Compressed G1 generator (1·G) — blstrs_plus serde test vector. */
    public static final String G1_GENERATOR =
            "97f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac58"
                    + "6c55e83ff97a1aeffb3af00adb22c6bb";

    /** Compressed 2·G on BLS12-381 G1. */
    public static final String G1_TIMES_2 =
            "a572cbea904d67468808c8eb50a9450c9721db309128012543902d0ac358a62a"
                    + "e28f75bb8f1c7c42c39a8c5529bf0f4e";

    /** Compressed 3·G on BLS12-381 G1. */
    public static final String G1_TIMES_3 =
            "89ece308f9d1f0131765212deca99697b112d61f9be9a5f1f3780a51335b3ff9"
                    + "81747a0b2ca2179b96d2c0c9024e5224";

    /** Compressed 5·G on BLS12-381 G1. */
    public static final String G1_TIMES_5 =
            "b0e7791fb972fe014159aa33a98622da3cdc98ff707965e536d8636b5fcc5ac7"
                    + "a91a8c46e59a00dca575af0f18fb13dc";

    /** Compressed 7·G on BLS12-381 G1. */
    public static final String G1_TIMES_7 =
            "b928f3beb93519eecf0145da903b40a4c97dca00b21f12ac0df3be9116ef2ef2"
                    + "7b2ae6bcd4c5bc2d54ef5a70627efcb7";

    /** Compressed G2 generator — blstrs_plus serde test vector (96 bytes, 192 hex chars). */
    public static final String G2_GENERATOR =
            "93e02b6052719f607dacd3a088274f65596bd0d09920b61ab5da61bbdc7f5049"
                    + "334cf11213945d57e5ac7d055d042b7e"
                    + "024aa2b2f08f0a91260805272dc51051c6e47ad4fa403b02b4510b647ae3d177"
                    + "0bac0326a805bbefd48056c8c121bdb8";

    /** Canonical publicKeys payload for revocation registry definition fixtures. */
    public static final String REVOCATION_PUBLIC_KEYS_JSON =
            "{\"revocationVerifyingKey\":\"" + G2_GENERATOR + "\"}";

    /**
     * Gherkin accumulator aliases: feature files keep readable names while the wire carries genuine
     * compressed G1 points.
     */
    private static final Map<String, String> ACCUM_ALIASES =
            Map.of(
                    "accum-genesis", G1_GENERATOR,
                    "accum-after-revocation", G1_TIMES_2,
                    "accum-unauthorized", G1_TIMES_3,
                    "accum-after-event", G1_TIMES_5,
                    "accum-second", G1_TIMES_7);

    /** Utility class constructor is private to prevent instantiation. */
    private TestVectors() {}

    /**
     * Resolves a feature-file accumulator alias to its genuine compressed G1 hex value.
     *
     * @param alias readable alias used in Gherkin scenarios
     * @return compressed G1 point as 96 lowercase hex characters
     * @throws IllegalArgumentException for unknown aliases, so typos fail fast
     */
    public static String accumValue(final String alias) {
        final String value = ACCUM_ALIASES.get(alias);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Unknown accumulator alias '" + alias + "'; add it to TestVectors");
        }
        return value;
    }
}
