package de.pyryco.mobile.design

/**
 * Keeps one method of a design capture class in the verifier's per-ticket device run.
 *
 * A device test class whose name ends in `CaptureTest` is a design capture class. The verifier's
 * per-ticket device step leaves those classes out and the main sweep runs them, because on the
 * headless gate emulator captures save metadata only and the design evidence comes from the
 * Pixel 8 run. A method in such a class that checks behaviour and saves no design frame carries
 * this annotation, so it still runs on every ticket. The pipeline's device gate reads it from the
 * source text: put it directly on the test method.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class PerTicketDeviceTest
