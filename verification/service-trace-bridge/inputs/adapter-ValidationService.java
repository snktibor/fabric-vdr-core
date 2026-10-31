package com.example.vdr.logic;
import java.util.List;
import com.example.vdr.exception.VdrException;
public class ValidationService {
public RevRegEntryGuard[] typedGuards;
public int[] nonselfBefore;
//@ public ghost int failureIndex;

public String idOutput;
public String accumOutput;
//@ public ghost boolean idValid;
//@ public ghost boolean accumValid;
//@ public ghost boolean guardsValid;
public List guards;
/*@ public normal_behavior
 @ requires idValid;
 @ ensures \result == idOutput;
 @ assignable \strictly_nothing;
 @ also public exceptional_behavior
 @ requires !idValid;
 @ signals_only VdrException;
 @ signals (VdrException e) e.errorCode == "INVALID_ARGUMENT";
 @ assignable \strictly_nothing;
 @*/
 public /*@ helper @*/ native String requireNotBlank(String input, String field);
/*@ public normal_behavior
 @ requires accumValid;
 @ ensures \result == accumOutput;
 @ assignable \strictly_nothing;
 @ also public exceptional_behavior
 @ requires !accumValid;
 @ signals_only VdrException;
 @ signals (VdrException e) e.errorCode == "INVALID_ARGUMENT";
 @ assignable \strictly_nothing;
 @*/
 public /*@ helper @*/ native String requireCompressedG1Hex(String input, String field);
/*@ public normal_behavior
 @ requires guardsValid;
 @ ensures \result == guards;
 @ assignable \strictly_nothing;
 @ also public exceptional_behavior
 @ requires !guardsValid;
 @ signals_only VdrException;
 @ signals (VdrException e) e.errorCode == "INVALID_ARGUMENT";
 @ assignable \strictly_nothing;
 @*/
 public /*@ helper @*/ native List parseRevRegEntryGuards(String input, String field);
}
