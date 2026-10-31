// PROOF ADAPTER STUB: not production implementation.
package io.github.snktibor.vdr.ledger; import io.github.snktibor.vdr.model.RevRegEntryRecord; import java.util.List; public class RevRegEntryRegistry { public RevRegEntryRecord get(String id); public boolean exists(String id); public void put(String id, RevRegEntryRecord record); public List history(String id); }
