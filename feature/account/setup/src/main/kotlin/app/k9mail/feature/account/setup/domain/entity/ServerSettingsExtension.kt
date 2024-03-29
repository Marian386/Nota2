package app.k9mail.feature.account.setup.domain.entity

import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ServerSettings

fun ServerSettings?.isOAuth() = this?.authenticationType == AuthType.XOAUTH2
