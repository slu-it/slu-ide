package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.HclHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/**
 * Shows HCL: Terraform (`.tf`, `.tfvars`), `.hcl` (also `.pkr.hcl` and `.terraform.lock.hcl`) and Nomad (`.nomad`)
 * with syntax highlighting ([HclHighlighter]). HCL also accepts `#` comments; toggling uses `//`.
 */
@Component
class TerraformFileViewer : CodeFileViewer(setOf("tf", "tfvars", "hcl", "nomad"), HclHighlighter, CommentSyntax.SLASHES)
