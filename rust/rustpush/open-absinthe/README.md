# Open Absinthe

Computes Mac validation data from a stored hardware identity.

The implementation is closed source. What is tracked here is the public interface
(`nac::HardwareConfig`, `nac::ValidationCtx`, `AbsintheError`) with a stub behind it, so the rest of
the project builds and runs: every validation call returns an "unavailable" error, and a build made
from these sources has to register through a validation relay instead.

The real backend is compiled in by the `emulator` feature, which expects its sources in a `private/`
directory at the repository root. Gradle enables the feature when that directory is present.
