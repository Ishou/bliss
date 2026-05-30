package com.bliss.survey.application

import java.time.Duration

// ADR-0059: both undo expiry and export settle cutoff derive from the same grace window.
val CLOSE_GRACE: Duration = Duration.ofSeconds(8)
