package io.github.vigoo.prox

sealed trait ProxError {
  def toThrowable: Throwable
}

final case class FailedToReadProcessOutputException(reason: Throwable) extends Exception(s"Failed to read process output", reason)
final case class FailedToReadProcessOutput(reason: Throwable) extends ProxError {
  override def toThrowable: Throwable = FailedToReadProcessOutputException(reason)
}

final case class FailedToWriteProcessInputException(reason: Throwable) extends Exception(s"Failed to write process input", reason)
final case class FailedToWriteProcessInput(reason: Throwable) extends ProxError {
  override def toThrowable: Throwable = FailedToWriteProcessInputException(reason)
}

final case class UnknownProxErrorException(reason: Throwable) extends Exception(s"Unknown prox failure", reason)
final case class UnknownProxError(reason: Throwable) extends ProxError {
  override def toThrowable: Throwable = UnknownProxErrorException(reason)
}

final case class MultipleProxErrorsException(value: List[ProxError]) extends Exception(s"Multiple prox failures: ${value.mkString(", ")}")
final case class MultipleProxErrors(errors: List[ProxError]) extends ProxError {
  override def toThrowable: Throwable = MultipleProxErrorsException(errors)
}

final case class FailedToQueryStateException(reason: Throwable) extends Exception(s"Failed to query state of process", reason)
final case class FailedToQueryState(reason: Throwable) extends ProxError {
  override def toThrowable: Throwable = FailedToQueryStateException(reason)
}

final case class FailedToDestroyException(reason: Throwable) extends Exception(s"Failed to destroy process", reason)
final case class FailedToDestroy(reason: Throwable) extends ProxError {
  override def toThrowable: Throwable = FailedToDestroyException(reason)
}

final case class FailedToWaitForExitException(reason: Throwable) extends Exception(s"Failed to wait for process to exit", reason)
final case class FailedToWaitForExit(reason: Throwable) extends ProxError {
  override def toThrowable: Throwable = FailedToWaitForExitException(reason)
}

final case class FailedToStartProcessException(reason: Throwable) extends Exception(s"Failed to start process", reason)
final case class FailedToStartProcess(reason: Throwable) extends ProxError {
  override def toThrowable: Throwable = FailedToStartProcessException(reason)
}
