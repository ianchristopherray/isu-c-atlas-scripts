package atlas.c.xinu;

import static com.ensoftcorp.atlas.java.core.script.Common.universe;
import static com.ensoftcorp.atlas.java.core.script.Common.edges;
import static com.ensoftcorp.atlas.java.core.script.CommonQueries.methodParameter;

import com.ensoftcorp.atlas.core.db.graph.Graph;
import com.ensoftcorp.atlas.core.xcsg.XCSG;
import com.ensoftcorp.atlas.c.core.query.Attr.Edge;
import com.ensoftcorp.atlas.java.core.highlight.H;
import com.ensoftcorp.atlas.java.core.highlight.Highlighter;
import com.ensoftcorp.atlas.java.core.highlight.Highlighter.ConflictStrategy;
import com.ensoftcorp.atlas.java.core.query.Q;
import com.ensoftcorp.atlas.java.core.script.Common;
import com.ensoftcorp.atlas.ui.viewer.graph.DisplayUtil;

public class Xinu {

	public Q withinFile(String name) {
		return edges(XCSG.Contains).forward(file("test.c")).induce(universe());
	}
	
	public Q file(String name) { 
		return universe().nodesTaggedWithAny(XCSG.C.TranslationUnit).selectNode(XCSG.name,name); 
	}
	
	public Q javaclass(String name) { 
		return universe().nodesTaggedWithAny(XCSG.Java.Class).selectNode(XCSG.name,name); 
	}
	
	public Q function(String name) { 
		return universe().nodesTaggedWithAll(XCSG.Function, "isDef").selectNode(XCSG.name, name); 
	}
	
	public Q rcg(Q q) { 
		return edges(XCSG.Call).reverse(q); 
	}
	
	public Q typeOf(Q q) {
		Q res = Common.edges(XCSG.TypeOf, Edge.ELEMENTTYPE, "arrayOf", "pointerOf").forward(q);
		return res;
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