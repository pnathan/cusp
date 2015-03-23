package jasko.tim.lisp.editors.actions;

public class HyperSpecAction extends CallUrlAction implements IEditorAction {

   private String uri;
   
   public HyperSpecAction() {}
   
   public HyperSpecAction(Object object, String string) {
      super(object, string);
      
      this.uri = string;
   }
   
   public void run() {}
   
   
}