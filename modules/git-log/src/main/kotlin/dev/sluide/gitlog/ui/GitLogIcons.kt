package dev.sluide.gitlog.ui

import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.materialdesign2.MaterialDesignA
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignD
import org.kordamp.ikonli.materialdesign2.MaterialDesignF
import org.kordamp.ikonli.materialdesign2.MaterialDesignH
import org.kordamp.ikonli.materialdesign2.MaterialDesignP
import org.kordamp.ikonli.materialdesign2.MaterialDesignS
import org.kordamp.ikonli.materialdesign2.MaterialDesignT
import org.kordamp.ikonli.materialdesign2.MaterialDesignU

/** The icon of each git log action; the header buttons and the branch and commit menus use the same one. */
internal object GitLogIcons {
    val review: Ikon = MaterialDesignF.FILE_EYE_OUTLINE
    val fetch: Ikon = MaterialDesignA.ARROW_DOWN
    val push: Ikon = MaterialDesignA.ARROW_UP
    val rebase: Ikon = MaterialDesignS.SOURCE_BRANCH_SYNC
    val pull: Ikon = MaterialDesignS.SOURCE_PULL
    val newBranch: Ikon = MaterialDesignS.SOURCE_BRANCH_PLUS
    val checkout: Ikon = MaterialDesignS.SOURCE_BRANCH_CHECK
    val merge: Ikon = MaterialDesignS.SOURCE_MERGE
    val compare: Ikon = MaterialDesignC.COMPARE_HORIZONTAL
    val rename: Ikon = MaterialDesignP.PENCIL_OUTLINE
    val delete: Ikon = MaterialDesignD.DELETE_OUTLINE
    val workingTreeDiff: Ikon = MaterialDesignF.FILE_COMPARE
    val copy: Ikon = MaterialDesignC.CONTENT_COPY
    val newTag: Ikon = MaterialDesignT.TAG_PLUS_OUTLINE
    val cherryPick: Ikon = MaterialDesignF.FRUIT_CHERRIES
    val revert: Ikon = MaterialDesignU.UNDO_VARIANT
    val reset: Ikon = MaterialDesignH.HISTORY
    val editMessage: Ikon = MaterialDesignC.COMMENT_EDIT_OUTLINE
    val branches: Ikon = MaterialDesignS.SOURCE_BRANCH
}
