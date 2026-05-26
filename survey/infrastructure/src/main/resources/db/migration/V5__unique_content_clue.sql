-- Same (mot, definition) is the same logical clue to a rater; V1 cross-batch re-rating window dropped.

CREATE UNIQUE INDEX survey_items_content_uniq
    ON survey_items (mot, definition)
    WHERE retired_at IS NULL;
