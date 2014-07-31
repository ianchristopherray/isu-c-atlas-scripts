package atlas.c.scripts;

import static com.ensoftcorp.atlas.java.core.script.Common.edges;
import static com.ensoftcorp.atlas.java.core.script.Common.universe;
import static com.ensoftcorp.atlas.java.core.script.CommonQueries.methodParameter;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import com.ensoftcorp.atlas.c.core.query.Attr.Edge;
import com.ensoftcorp.atlas.core.db.graph.Graph;
import com.ensoftcorp.atlas.core.db.graph.GraphElement;
import com.ensoftcorp.atlas.core.db.graph.GraphElement.NodeDirection;
import com.ensoftcorp.atlas.core.db.set.AtlasHashSet;
import com.ensoftcorp.atlas.core.db.set.AtlasSet;
import com.ensoftcorp.atlas.core.index.common.SourceCorrespondence;
import com.ensoftcorp.atlas.core.xcsg.XCSG;
import com.ensoftcorp.atlas.java.core.query.Q;
import com.ensoftcorp.atlas.java.core.script.Common;

public class Queries {
	
	public static String EVENT_FLOW_EDGE = "eventFlow";
	
	public static String EVENT_FLOW_NODE = "eventFlow";
	
	/**
	 * Returns the set of nodes that correspond to a definition of the function given by "name"
	 * @param name: The name of function
	 * @return The set of nodes representing a definition for that function
	 */
	public Q function(String name) { 
		return universe().nodesTaggedWithAll(XCSG.Function, "isDef").selectNode(XCSG.name, name); 
	}
	
	/**
	 * Returns the global variable given by the name
	 * @param name
	 * @return global variable by the given name
	 */
	public Q global(String name){
		return universe().nodesTaggedWithAll(XCSG.GlobalVariable).selectNode(XCSG.name, name);
	}
	
	/**
	 * Returns the CFG of a given function name
	 * @param name: The name of function
	 * @return The CFG of a function
	 */
	public Q CFG(String name){
		Q method = function(name);
		Q cfg = edges(XCSG.Contains).forward(method).nodesTaggedWithAny(XCSG.ControlFlow_Node).induce(edges(XCSG.ControlFlow_Edge));
		return cfg;
	}
	
	/**
	 * Returns the Event Flow Graph of a given function name
	 * @param name: The name of function
	 * @return The EFG of a function
	 */
	public Q EFG(String name){
		Q method = function(name);
		Q efg = universe().edgesTaggedWithAny(XCSG.Contains, EVENT_FLOW_EDGE).forward(method).nodesTaggedWithAny(EVENT_FLOW_NODE).induce(universe().edgesTaggedWithAll(EVENT_FLOW_EDGE));
		return efg;
	}
	
	/**
	 * Returns the call graph for the given function
	 * @param function
	 * @return the set of functions called directly/indirectly by a given function
	 */
	public Q cg(Q function){
		return edges(XCSG.Call).forward(function);
	}
	
	/**
	 * Returns the reverse call graph of a given function
	 * @param function
	 * @return the set of functions the directly/indirectly call a given function
	 */
	public Q rcg(Q function){
		return edges(XCSG.Call).reverse(function);
	}
	
	/**
	 * Returns the set of function directly calling a given function
	 * @param function
	 * @return direct callers of a given function
	 */
	public Q call(Q function){
		return edges(XCSG.Call).reverseStep(function).roots();
	}
	
	/**
	 * Returns the set of functions that are directly called  by a given function
	 * @param function
	 * @return direct callees by a given function
	 */
	public Q calledby(Q function){
		return edges(XCSG.Call).forwardStep(function).leaves();
	}
	
	/**
	 * Returns the set of arguments passed to the given function
	 * @param function
	 * @param index
	 * @return the set of variables passed as a parameter to a given argument
	 */
	public Q argto(Q function, int index){
		return methodParameter(function, 0).roots();
	}
	
	/**
	 * Returns the set of functions referencing the specified argument
	 * @param arg
	 * @return the set of functions referencing (read/write) the specified argument
	 */
	public Q ref(Q arg){
		GraphElement node = arg.eval().nodes().getFirst();
		AtlasSet<GraphElement> inEdges = Graph.U.edges(node, NodeDirection.IN);
		AtlasSet<GraphElement> declReprEdges = inEdges.taggedWithAny(XCSG.Contains, Edge.REPR);
		if(inEdges.size() == declReprEdges.size())
			return arg;
		
		for(GraphElement e : declReprEdges){
			inEdges.remove(e);
		}
		Q edges = Common.toQ(Common.toGraph(inEdges));
		return edges.reverseStep(arg);
	}
	
	/**
	 * Returns the call site nodes for a given function
	 * @param functions
	 * @return The call site nodes
	 */
	public Q functionReturn(Q functions) {
		return edges(XCSG.Contains).forwardStep(functions).nodesTaggedWithAll(XCSG.MasterReturn);
	}
	
	/**
	 * Returns the function node that contains the given query
	 * @param q
	 * @return The function node
	 */
	public Q getFunctionContainingElement(Q q){
		Q declares = edges(XCSG.Contains).reverseStep(q);
		declares = edges(XCSG.Contains).reverseStep(declares);
		return declares.nodesTaggedWithAll(XCSG.Function, "isDef");
	}
	
	public Q getFunctionContainingElement(GraphElement e){
		return getFunctionContainingElement(Common.toQ(Common.toGraph(e)));
	}
	
	/**
	 * Returns the type of for a given query
	 * @param q
	 * @return The type nodes
	 */
	public Q typeOf(Q q) {
		Q res = Common.edges(XCSG.TypeOf, Edge.ELEMENTTYPE, "arrayOf", "pointerOf").forward(q);
		return res;
	}
	
	/**
	 * Returns the Matching Pair Graph for given set of call sites
	 * @param callSiteNodes
	 * @param e1
	 * @param e2
	 * @return the matching pair Graph
	 */
	public Q mpg(Q callSiteNodes, HashSet<String> e1, HashSet<String> e2){
		AtlasSet<GraphElement> nodes = callSiteNodes.eval().nodes();
		HashMap<GraphElement, HashMap<String, AtlasSet<GraphElement>>> functionMap = new HashMap<GraphElement, HashMap<String,AtlasSet<GraphElement>>>(); 
		
		for(GraphElement node : nodes){
			Q functionQ = getFunctionContainingElement(node);
			GraphElement functionNode = functionQ.eval().nodes().getFirst();
			
			boolean callingLock = false;
			boolean callingUnlock = false;
			if(isCalling(node, e1)){
				callingLock = true;
			}
			
			if(isCalling(node, e2)){
				callingUnlock = true;
			}
			
			if(callingLock || callingUnlock){
				HashMap<String, AtlasSet<GraphElement>> luMap = new HashMap<String, AtlasSet<GraphElement>>();
				
				if(functionMap.containsKey(functionNode)){
					luMap = functionMap.get(functionNode);
				}
				
				if(callingLock){
					AtlasSet<GraphElement> callL = new AtlasHashSet<GraphElement>();
					if(luMap.containsKey("L")){
						callL = luMap.get("L");
					}
					callL.add(node);
					luMap.put("L", callL);
				}
				
				if(callingUnlock){
					AtlasSet<GraphElement> callU = new AtlasHashSet<GraphElement>();
					if(luMap.containsKey("U")){
						callU = luMap.get("U");
					}
					callU.add(node);
					luMap.put("U", callU);
				}
				functionMap.put(functionNode, luMap);
			}
		}
			
		AtlasSet<GraphElement> callL = new AtlasHashSet<GraphElement>();
		AtlasSet<GraphElement> callU = new AtlasHashSet<GraphElement>();
		AtlasSet<GraphElement> unbalanced = new AtlasHashSet<GraphElement>();
		
		for(GraphElement f : functionMap.keySet()){
			HashMap<String, AtlasSet<GraphElement>> nodesMap = functionMap.get(f);
			if(nodesMap.size() == 1 && nodesMap.keySet().contains("L")){
				callL.add(f);
				continue;
			}
			
			if(nodesMap.size() == 1 && nodesMap.keySet().contains("U")){
				callU.add(f);
				continue;
			}
			
			callL.add(f);
			callU.add(f);
			
			AtlasSet<GraphElement> lNodes = nodesMap.get("L");
			List<Integer> ls = new ArrayList<Integer>();
			for(GraphElement l : lNodes){
				SourceCorrespondence sc = (SourceCorrespondence) l.attr().get(XCSG.sourceCorrespondence);
				ls.add(sc.offset);
			}
			AtlasSet<GraphElement> uNodes = nodesMap.get("U");
			List<Integer> us = new ArrayList<Integer>();
			for(GraphElement u : uNodes){
				SourceCorrespondence sc = (SourceCorrespondence) u.attr().get(XCSG.sourceCorrespondence);
				us.add(sc.offset);
			}
			
			Collections.sort(ls);
			Collections.sort(us);
			
			if(us.get(us.size() - 1) <= ls.get(ls.size() - 1)){
				unbalanced.add(f);
				callU.remove(f);
			}
		}
		
		Q callLQ = Common.toQ(Common.toGraph(callL));
		Q callUQ = Common.toQ(Common.toGraph(callU));
		//Q callLU = callLQ.intersection(callUQ);
		Q rcg_lock = universe().edgesTaggedWithAll(XCSG.Call).reverse(callLQ);
		Q rcg_unlock = universe().edgesTaggedWithAll(XCSG.Call).reverse(callUQ);
		Q rcg_both = rcg_lock.intersection(rcg_unlock);
		Q rcg_c = rcg_lock.union(rcg_unlock);
		Q rcg_lock_only = rcg_lock.difference(rcg_both);
		Q rcg_unlock_only = rcg_unlock.difference(rcg_both);
		Q call_lock_only = callLQ.union(universe().edgesTaggedWithAll(XCSG.Call).reverseStep(rcg_lock_only));
		Q call_unlock_only = callUQ.union(universe().edgesTaggedWithAll(XCSG.Call).reverseStep(rcg_unlock_only));
		Q call_c_only = call_lock_only.union(call_unlock_only);
		Q balanced = call_c_only.intersection(rcg_both);
		Q ubc = balanced.union(rcg_lock_only, rcg_unlock_only);
		Q mpg = rcg_c.intersection(universe().edgesTaggedWithAll(XCSG.Call).forward(ubc));
		
		return mpg;
	}
	
	public boolean isCalling(GraphElement node, HashSet<String> functions){
		for(String f : functions){
			if(((String) node.attr().get(XCSG.name)).contains(f + "("))
				return true;
		}
		return false;
	}
	
	public boolean isCalling(GraphElement node, HashSet<String> functions, FileWriter writer){
		for(String f : functions){
			try {
				writer.write((String) node.attr().get(XCSG.name) + "\t" + f + "\n");
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			if(((String) node.attr().get(XCSG.name)).contains(f + "("))
				return true;
		}
		return false;
	}
	
	/**
	 * Delete EFG tags from the index
	 */
	public void deleteEFGs(){
		AtlasSet<GraphElement> edges = universe().edgesTaggedWithAll(EVENT_FLOW_EDGE).eval().edges();
		HashSet<GraphElement> toDelete = new HashSet<GraphElement>(); 
		for(GraphElement edge : edges){
			toDelete.add(edge);
		}
		
		for(GraphElement edge : toDelete){
			Graph.U.delete(edge);
		}
		
		AtlasSet<GraphElement> nodes = universe().nodesTaggedWithAll(EVENT_FLOW_NODE).eval().nodes();
		
		HashSet<GraphElement> ns = new HashSet<GraphElement>();
		for(GraphElement node : nodes){
			ns.add(node);
		}
		
		toDelete = new HashSet<GraphElement>(); 
		for(GraphElement node : ns){
			node.tags().remove(EVENT_FLOW_NODE);
			String name = (String) node.attr().get(XCSG.name);
			if(name.equals("EFG Entry") || name.equals("EFG Exit")){
				toDelete.add(node);
			}
		}
		
		for(GraphElement node : toDelete){
			Graph.U.delete(node);
		}
	}
}
