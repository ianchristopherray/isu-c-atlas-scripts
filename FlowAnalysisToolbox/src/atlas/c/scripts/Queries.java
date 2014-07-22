package atlas.c.scripts;

import static com.ensoftcorp.atlas.java.core.script.Common.edges;
import static com.ensoftcorp.atlas.java.core.script.Common.universe;
import static com.ensoftcorp.atlas.java.core.script.CommonQueries.methodParameter;

import com.ensoftcorp.atlas.c.core.query.Attr.Edge;
import com.ensoftcorp.atlas.core.db.graph.Graph;
import com.ensoftcorp.atlas.core.db.graph.GraphElement;
import com.ensoftcorp.atlas.core.db.graph.GraphElement.NodeDirection;
import com.ensoftcorp.atlas.core.db.set.AtlasSet;
import com.ensoftcorp.atlas.core.xcsg.XCSG;
import com.ensoftcorp.atlas.java.core.query.Q;
import com.ensoftcorp.atlas.java.core.script.Common;

public class Queries {
	
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
}
