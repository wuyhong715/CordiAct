package com.cordiact.core.render

/**
 * The tool an agent calls to put markup in front of a human.
 *
 * It is a *queue* operation, not a render: nothing is loaded, nothing is executed, and no
 * WebView exists until the user opens the preview window and presses 渲染. That split is what
 * keeps a chatty agent from burning CPU and memory on things nobody looks at.
 */
const val PREVIEW_PUSH_TOOL_ID = "preview.push"

/**
 * Caps applied before anything is stored. A preview is a look at a document, not a file
 * transfer; past these sizes the actor should be writing a file and referencing it instead.
 */
const val PREVIEW_MAX_HTML_CHARS = 200_000
const val PREVIEW_MAX_CSS_CHARS = 100_000
const val PREVIEW_MAX_TITLE_CHARS = 120
