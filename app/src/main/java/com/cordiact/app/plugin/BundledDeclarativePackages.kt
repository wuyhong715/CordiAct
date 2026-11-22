package com.cordiact.app.plugin

import com.cordiact.core.plugin.ExtensionPackageManifest
import com.cordiact.core.plugin.ExtensionPackageSource

/**
 * One bundled declarative package that exercises both contribution kinds in the shipping app.
 * It is protected host content: the manifest is compiled from these exact bytes, and the package
 * cannot be replaced through the user plugin path.
 */
object BundledDeclarativePackages {
    private const val SOURCE = """
{
  "schemaVersion": 1,
  "id": "core-quick-notes",
  "version": "1.0.0",
  "title": "Quick notes",
  "description": "Bundled example of a declarative tool and slash command.",
  "host": {"api": 1, "minVersion": "0.1.0"},
  "capabilities": ["tool.publish", "command.publish"],
  "tools": [
    {
      "name": "checklist",
      "description": "Show a short release checklist.",
      "kind": "static_text",
      "text": "1. Review the diff\n2. Run focused tests\n3. Report evidence"
    }
  ],
  "commands": [
    {
      "name": "release-checklist",
      "aliases": ["checklist"],
      "description": "Show the release checklist.",
      "text": "1. Review the diff\n2. Run focused tests\n3. Report evidence"
    }
  ]
}
"""

    val manifest: ExtensionPackageManifest by lazy {
        ExtensionPackageSource.compile(SOURCE.toByteArray(Charsets.UTF_8))
    }
}
