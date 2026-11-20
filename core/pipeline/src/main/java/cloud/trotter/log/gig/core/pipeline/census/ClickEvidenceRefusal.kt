package cloud.trotter.log.gig.core.pipeline.census

/**
 * #1634: why a tap produced no click evidence. Counted in `PipelineStats`, never logged with text. A refusal never
 * changes the click observation itself.
 */
enum class ClickEvidenceRefusal {
    /** Click evidence was not armed (census off, the server does not list the clicked schema, or an older consent). */
    UNARMED,

    /** The tap's window or its root could not be read, belongs to another window or package, or hit a tree cap. */
    NO_WINDOW,

    /** The tapped node was not found in its window's tree (the window changed first, or a child could not be read). */
    SOURCE_GONE,

    /** Reading the window threw; the click went on without evidence. */
    ACQUIRE_FAILED,

    /** The tap's own window classified as a sensitive or noise screen. */
    BLOCKED,

    /** The click was classified with no rules generation, or one without an id. */
    NO_GENERATION,

    /** The tapped node has no node that takes the click within the owner walk. */
    NO_OWNER,

    /** The owner node is not in the built skeleton. */
    OWNER_WITHHELD,

    /** The builder's whole-window or title sensitive scan hit. */
    SENSITIVE,

    /** A census deletion or identity reset ran after the window was read. */
    STALE_BOUNDARY,
}
