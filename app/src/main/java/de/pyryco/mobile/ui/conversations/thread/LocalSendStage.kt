package de.pyryco.mobile.ui.conversations.thread

/** Client send progress; none of these stages establishes a running, interruptible daemon turn. */
enum class LocalSendStage { None, Sending, Waiting }
