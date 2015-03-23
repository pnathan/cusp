package jasko.tim.lisp.editors.actions;

import jasko.tim.lisp.LispPlugin;
import jasko.tim.lisp.editors.ILispEditor;
import jasko.tim.lisp.swank.*;


public class DisassembleAction extends LispAction implements IEditorAction {
	
	public DisassembleAction() {
	}
	
	public DisassembleAction(ILispEditor editor) {
		super(editor);
	}
	
	/* (non-Javadoc)
    * @see jasko.tim.lisp.editors.actions.IEditorAction#run()
    */
	@Override
   public void run() {
		String sym = getSymbol();

		LispPlugin.getDefault().getSwank().sendDisassemble(sym, getPackage(),
				new SwankRunnable() {
			public void run() {
				String assembly = result.getf(":return").getf(":ok").value;
				if (assembly.equalsIgnoreCase("nil")) {
					editor.showMessage("Function not found.");
				} else {
					editor.showMessage(assembly);
				}
			}
		});
	}

}
