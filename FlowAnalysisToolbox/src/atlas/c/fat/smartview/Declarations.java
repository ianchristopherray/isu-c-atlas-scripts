package atlas.c.fat.smartview;

import java.awt.Color;

import com.ensoftcorp.atlas.core.xcsg.XCSG;
import com.ensoftcorp.atlas.java.core.highlight.Highlighter;
import com.ensoftcorp.atlas.java.core.query.Attr.Edge;
import com.ensoftcorp.atlas.java.core.query.Q;
import com.ensoftcorp.atlas.java.core.script.Common;
import com.ensoftcorp.atlas.java.core.script.StyledResult;
import com.ensoftcorp.atlas.java.ui.scripts.selections.AtlasSmartViewScript;

public class Declarations implements AtlasSmartViewScript{
	@Override
	public String[] getSupportedNodeTags() {
		return new String[]{};
	}

	@Override
	public String[] getSupportedEdgeTags() {
		return new String[]{};
	}

	@Override
	public StyledResult selectionChanged(SelectionInput input) {
		Q interpretedSelection = input.getInterpretedSelection();
		
		Q res = Common.edges(XCSG.Contains).forward(interpretedSelection).induce(Common.universe());
		Highlighter h = new Highlighter();
		h.highlightEdges(Common.edges(XCSG.LoopChild), Color.BLUE);
		h.highlightEdges(Common.edges(XCSG.ControlFlowBackEdge), Color.RED);
		
		return new StyledResult(res, h);
	}

	@Override
	public String getTitle() {
		return "Custom Declarations";
	}
}