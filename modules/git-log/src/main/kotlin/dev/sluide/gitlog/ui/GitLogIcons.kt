package dev.sluide.gitlog.ui

import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.materialdesign2.MaterialDesignA
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignD
import org.kordamp.ikonli.materialdesign2.MaterialDesignF
import org.kordamp.ikonli.materialdesign2.MaterialDesignP
import org.kordamp.ikonli.materialdesign2.MaterialDesignS

/** The icon of each git log action; the header buttons and the branch menus use the same one. */
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
}
