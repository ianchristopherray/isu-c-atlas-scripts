package com.iastate.verifier.internal;
public class PathStatus 
{
    final public static int UNKNOWN = 0;
    final public static int MATCH = 1;
    final public static int LOCK = 2;
    final public static int UNLOCK = 4;
    final public static int THROUGH = 8;
    final public static int START = 16;
    final public static int ERROR = 32;
    
    public static String PathStatusToText(int value)
    {
    	String ret = "";
    	if(value == UNKNOWN)
    		ret = "UNKNOWN,";
    	else
    	{
            if((value | MATCH) == value)
                ret += "MATCH,";
            if((value | LOCK) == value)
                ret += "LOCK,";
            if((value | UNLOCK) == value)
                ret += "UNLOCK,";
            if((value | THROUGH) == value)
                ret += "THROUGH,";
            if((value | START) == value)
                ret += "START,";
            if((value | ERROR) == value)
                ret += "ERROR,";   		
    	}
    	return ret;
    }
}