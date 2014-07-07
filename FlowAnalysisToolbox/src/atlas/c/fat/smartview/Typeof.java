package atlas.c.fat.smartview;

import java.awt.Color;

import com.ensoftcorp.atlas.core.xcsg.XCSG;
import com.ensoftcorp.atlas.java.core.highlight.Highlighter;
import com.ensoftcorp.atlas.java.core.query.Attr.Edge;
import com.ensoftcorp.atlas.java.core.query.Attr.Node;
import com.ensoftcorp.atlas.java.core.query.Q;
import com.ensoftcorp.atlas.java.core.script.Common;
import com.ensoftcorp.atlas.java.core.script.StyledResult;
import com.ensoftcorp.atlas.java.ui.scripts.selections.AtlasSmartViewScript;
/**
 * Does a 2 step reverse dataflow
 *
 */
public class Typeof implements AtlasSmartViewScript {

	@Override
	public String[] getSupportedNodeTags() {
		return new String[]{Node.DATA_FLOW, XCSG.DataFlow_Node, 
				Node.VARIABLE, XCSG.Variable,
				Node.METHOD, XCSG.Method, XCSG.Function,
				Node.TYPE, XCSG.Type, 
				XCSG.TypeAlias};
	}

	@Override
	public String[] getSupportedEdgeTags() {
		return new String[] {};
	}

	@Override
	public StyledResult selectionChanged(SelectionInput input) {
		Q interpretedSelection = input.getInterpretedSelection();
		
		Q res = Common.edges(XCSG.TypeOf, Edge.ELEMENTTYPE, "arrayOf", "pointerOf").forward(interpretedSelection);
		Highlighter h = new Highlighter();
		h.highlightEdges(Common.edges(Edge.TYPEOF), Color.RED);

		
		return new StyledResult(res, h);
	}

	@Override
	public String getTitle() {
		return "Typeof edge inspector";
	}
}