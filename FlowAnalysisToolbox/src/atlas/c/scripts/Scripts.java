package atlas.c.scripts;

import static com.ensoftcorp.atlas.java.core.script.Common.universe;
import static com.ensoftcorp.atlas.java.core.script.Common.edges;
import static com.ensoftcorp.atlas.java.core.script.CommonQueries.methodParameter;

import java.util.HashSet;

import com.ensoftcorp.atlas.core.db.graph.Graph;
import com.ensoftcorp.atlas.core.db.graph.GraphElement;
import com.ensoftcorp.atlas.core.db.set.AtlasSet;
import com.ensoftcorp.atlas.core.xcsg.XCSG;
import com.ensoftcorp.atlas.java.core.highlight.H;
import com.ensoftcorp.atlas.java.core.highlight.Highlighter;
import com.ensoftcorp.atlas.java.core.highlight.Highlighter.ConflictStrategy;
import com.ensoftcorp.atlas.java.core.query.Q;
import com.ensoftcorp.atlas.java.core.script.Common;
import com.ensoftcorp.atlas.ui.viewer.graph.DisplayUtil;
import com.iastate.verifier.internal.Utils;

public class Scripts {
	
	public void deleteEFGs(){
		AtlasSet<GraphElement> edges = universe().edgesTaggedWithAll("eventFlow").eval().edges();
		HashSet<GraphElement> toDelete = new HashSet<GraphElement>(); 
		for(GraphElement edge : edges){
			toDelete.add(edge);
		}
		
		for(GraphElement edge : toDelete){
			Graph.U.delete(edge);
		}
	}
	
	public Q CFG(String name){
		Q method = function(name);
		Q cfg = edges(XCSG.Contains).forward(method).nodesTaggedWithAny(XCSG.ControlFlow_Node).induce(edges(XCSG.ControlFlow_Edge));
		return cfg;
	}
	
	public Q EFG(String name){
		Q method = function(name);
		Q efg = edges(XCSG.Contains).forward(method).nodesTaggedWithAny("eventFlow").induce(universe().edgesTaggedWithAll("eventFlow"));
		return efg;
	}
	
	public void analyze(){
		Q get = functionReturn(function("getbuf"));
		Q dfGet = edges(XCSG.DataFlow_Edge).forwardStep(get);
		Graph g = dfGet.eval();
		for(GraphElement e : g.leaves()){
			Q method = getDeclaringMethod(e);
			String currentMethod = method.eval().nodes().iterator().next().attr().get(XCSG.name).toString();
			Q q = analyze(currentMethod);
			q = Common.extend(q, XCSG.Contains);
			Graph graph = q.eval();
			Highlighter h = new Highlighter();
			h.highlight(dfGet, java.awt.Color.RED);
			DisplayUtil.displayGraph(graph, h, currentMethod);
		}		
	}
	
	public Q analyze(String function){
		Q get = functionReturn(function("getbuf"));
		Q dfGet = edges(XCSG.DataFlow_Edge).forwardStep(get);
		Graph g = dfGet.eval();
		Q eprime = null;
		for(GraphElement e : g.leaves()){
			Q method = getDeclaringMethod(e);
			String currentMethod = method.eval().nodes().iterator().next().attr().get(XCSG.name).toString();
			if(currentMethod.equals(function)){
				eprime = Common.toQ(Common.toGraph(e));
				break;
			}
		}
		Q dfGetForward = edges(XCSG.DataFlow_Edge).forwardStep(eprime);
		Q dataFlow = dfGetForward;
		long nodesCount = 0;
		long edgesCount = 0;
		do{
			Graph gprime = dataFlow.eval();
			nodesCount = gprime.nodes().size();
			edgesCount = gprime.edges().size();
			AtlasSet<GraphElement> leaves = gprime.leaves();
			for(GraphElement element : leaves){
				Q method = getDeclaringMethod(element);
				if(!method.eval().nodes().isEmpty()){
					String currentMethod = method.eval().nodes().iterator().next().attr().get(XCSG.name).toString();
					if(!currentMethod.equals("freebuf")){
						dataFlow = dataFlow.union(edges(XCSG.DataFlow_Edge).forwardStep(Common.toQ(Common.toGraph(element))));
					}	
				}else{
					dataFlow = dataFlow.union(edges(XCSG.DataFlow_Edge).forwardStep(Common.toQ(Common.toGraph(element))));
				}
			}
		}while(nodesCount != dataFlow.eval().nodes().size() && edgesCount != dataFlow.eval().edges().size());
		return dataFlow;
	}
	
	public Q getDeclaringMethod(GraphElement e){
		Q declares = edges(XCSG.Contains).reverseStep(Common.toQ(Common.toGraph(e)));
		declares = edges(XCSG.Contains).reverseStep(declares);
		Q nodes = declares.nodesTaggedWithAll(XCSG.Function, "isDef");
		return nodes;
	}
	
	public Q restrictOnControlNodes(Q dataFlow){
		dataFlow = Common.extend(dataFlow, XCSG.Contains);
		return dataFlow.nodesTaggedWithAll(XCSG.ControlFlow_Node);
	}
	
	public Q restrictOnFunctionCalls(Q dataFlow){
		dataFlow = Common.extend(dataFlow, XCSG.Contains);
		return dataFlow.nodesTaggedWithAll(XCSG.Function).induce(edges(XCSG.Call));		
	}
	
	public void highlightEventNodes(String function){
		deleteEFGs();
		Q dataFlow = analyze(function);
		Q functionCalls = restrictOnFunctionCalls(dataFlow);
		Q controlNodes = restrictOnControlNodes(dataFlow);
		
		AtlasSet<GraphElement> functions = functionCalls.nodesTaggedWithAll(XCSG.Function).eval().nodes();
		for(GraphElement functionCall : functions){
			String functionName = functionCall.attr().get(XCSG.name).toString();
			Q cfg = CFG(functionName);
			Q intersection = cfg.intersection(controlNodes);
			H h = new Highlighter(ConflictStrategy.COLOR);
			h.highlight(intersection, java.awt.Color.RED);
			//cfg = Common.extend(cfg, Edge.DECLARES);
			Graph g = cfg.eval();
			DisplayUtil.displayGraph(g, h, "CFG-" + functionName);
			
			Utils.addEFGToIndex(g, Utils.createEventFlowGraph(g, intersection.eval().nodes()));
			Q efg = EFG(functionName);
			
			DisplayUtil.displayGraph(efg.eval(), h, "EFG-" + functionName);
			
			//GraphUtils.write(Utils.transformAtlasGraph(g, intersection.eval().nodes()),"/home/ahmed/Desktop/cfg.dot");
			//GraphUtils.write(Utils.createEventFlowGraph(g, intersection.eval().nodes()),"/home/ahmed/Desktop/efg.dot");
		}
	}
	
	public Q function(String name) { 
		return universe().nodesTaggedWithAll(XCSG.Function, "isDef").selectNode(XCSG.name, name); 
	}
	
	public Q functionReturn(Q functions) {
		return edges(XCSG.Contains).forwardStep(functions).nodesTaggedWithAll(XCSG.MasterReturn);
	}

	public void bufferFlow() {
		Q get = functionReturn(function("getbuf"));
		Q free = methodParameter(function("freebuf"), 0);

		Q dfFree = edges(XCSG.DataFlow_Edge).reverse(free);
		Q dfGet = edges(XCSG.DataFlow_Edge).forward(get);
		
		H h = new Highlighter(ConflictStrategy.COLOR);
		h.highlight(dfGet, java.awt.Color.GREEN);
		h.highlight(dfFree, java.awt.Color.BLUE);
		Q q = dfFree.union(dfGet);
		q = Common.extend(q, XCSG.Contains);
		Graph g = q.eval();
		DisplayUtil.displayGraph(g, h);
		
	}
}
