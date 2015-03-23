package jasko.tim.lisp.builder;

import jasko.tim.lisp.LispPlugin;
import jasko.tim.lisp.SwankNotFoundException;
import jasko.tim.lisp.preferences.PreferenceConstants;
import jasko.tim.lisp.swank.LispNode;
import jasko.tim.lisp.swank.LispParser;
import jasko.tim.lisp.swank.SwankInterface;
import jasko.tim.lisp.swank.SwankRunnable;
import jasko.tim.lisp.util.LispUtil;
import jasko.tim.lisp.util.TopLevelItem;
import jasko.tim.lisp.views.ReplView;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.ui.PlatformUI;

public class LispBuilder extends IncrementalProjectBuilder {

   /**
    * This class updates compile markers in source code.
    * 
    * @author sk
    * 
    */
   public static class CompileListener extends SwankRunnable {
      String  asdFile              = "";
      boolean compiledByAsd        = false;
      IFile   file;
      int     length;
      int     offset;
      boolean removeCompileMarkers = true;
      boolean switchToPackage      = false; // only when compiledByAsd

      public CompileListener(final boolean compByAsd,
                             final String asdfile,
                             final boolean switchToPackage) {
         this.file = null;
         this.offset = 0;
         this.length = 0;
         this.compiledByAsd = true;
         this.asdFile = asdfile;
         this.switchToPackage = switchToPackage;
      }

      public CompileListener(final IFile file,
                             final boolean removeCompileMarkers) {
         this.file = file;
         this.offset = 0;
         this.length = 0;
         this.compiledByAsd = false;
         this.asdFile = "";
         this.removeCompileMarkers = removeCompileMarkers;
      }

      public CompileListener(final IFile file,
                             final int offset,
                             final int length) {
         this.file = file;
         this.offset = offset;
         this.length = length;
      }

      @Override
      public void run() {
         final ArrayList<String> files = new ArrayList<String>();
         if ((this.file != null) && (this.length == 0) &&
                  this.removeCompileMarkers) {
            LispMarkers.deleteCompileMarkers(this.file);
         }
         else
            if ((this.file != null) && (this.length > 0)) {
               LispMarkers.deleteCompileMarkers(this.file,
                                                this.offset,
                                                this.length);
            }

         final ReplView repl = (ReplView) PlatformUI.getWorkbench()
                                                    .getActiveWorkbenchWindow()
                                                    .getActivePage()
                                                    .findView(ReplView.ID);

         final LispNode res = this.result;
         final LispNode guts = res.getf(":return")
                                  .getf(":ok")
                                  .getf(":swank-compilation-unit");
         if (guts.isEmpty() || guts.value.equalsIgnoreCase("nil")) {
            if (this.compiledByAsd) {
               if (repl != null) {
                  repl.appendText("Loaded package " + this.asdFile + "\n");
                  final File asdf = new File(this.asdFile);
                  String pkg = asdf.getName();
                  pkg = pkg.substring(0, pkg.length() - ".asd".length());
                  repl.switchPackage(pkg);
               }
            }
         }
         else {
            int nerrors = 0;
            int nwarnings = 0;
            final IWorkspaceRoot wk = ResourcesPlugin.getWorkspace().getRoot();
            for (final LispNode error : guts.params) {
               try {
                  String msg = error.getf(":message").value;
                  // Make some messages in SBCL prettier

                  if (LispPlugin.getDefault().getSwank().implementation.lispType()
                                                                       .equalsIgnoreCase("SBCL")) {
                     final String[] lines = msg.split("\n");
                     final String lastLine = lines[lines.length - 1];
                     if (msg.endsWith("is defined but never used.") ||
                              lastLine.startsWith("undefined variable:")) {
                        msg = lastLine;
                     }
                  }

                  final String severity = error.getf(":severity").value;
                  final LispNode location = error.getf(":location");
                  final String fileName = location.getf(":file").value.replace("\\",
                                                                               "/");
                  final String buffer = location.getf(":buffer").value;
                  int offset = 0;
                  try {
                     offset = Integer.parseInt(location.getf(":position").value);
                  }
                  catch (final NumberFormatException e) {
                  }

                  final boolean isError = severity.equalsIgnoreCase(":error");
                  if (isError) {
                     ++nerrors;
                  }
                  else {
                     ++nwarnings;
                  }

                  if ((this.file == null) && !fileName.equals("")) {
                     final IFile fl = wk.getFileForLocation(new Path(fileName));
                     if (fl != null) {
                        if (this.compiledByAsd && !files.contains(fileName)) {
                           files.add(fileName);
                           LispMarkers.deleteCompileMarkers(fl);
                        }
                        LispMarkers.addCompileMarker(fl,
                                                     offset,
                                                     1,
                                                     msg,
                                                     isError);
                     }
                  }
                  else
                     if ((this.file != null) &&
                              (this.file.getLocation()
                                        .toPortableString()
                                        .equals(fileName) ||
                                       this.file.getName().equals(buffer) || this.file.getLocation()
                                                                                      .toPortableString()
                                                                                      .equals(buffer))) {
                        LispMarkers.addCompileMarker(this.file,
                                                     offset,
                                                     1,
                                                     msg,
                                                     isError);
                     }
                     else { // cannot resolve error location
                        // System.out.printf("CompileListener: Filename {%s} is not equal buffer {%s} or filename from compiler notes {%s}\n",
                        // file.getLocation().toString(), fileName, buffer);
                     }

               }
               catch (final SwankNotFoundException e1) {
                  // TODO Auto-generated catch block
                  e1.printStackTrace();
               }

            }
            if (this.compiledByAsd) {
               String torepl = "Package " + this.asdFile + " is loaded with ";
               if (nerrors > 0) {
                  torepl += "errors ";
                  // FIXME: some errors and warnings are duplicates - one with
                  // file location
                  // and another without - I don't know why
                  // so showing how many errors and warnings lisp reported might
                  // be confusing
                  if (nwarnings > 0) {
                     torepl = torepl + " and ";
                  }
               }
               if (nwarnings > 0) {
                  torepl += "warnings";
               }
               if (repl != null) {
                  repl.appendText(torepl);
               }
            }
         }

      }
   }

   /*
    * // We ended up not using asd files for incremental build. However these
    * couple of functions // might be useful for future code handling project
    * management with asd files. So I leave them commented out. private
    * ArrayList<IFile> asdFiles = null; private ArrayList<ArrayList<String>>
    * filesInAsd = null; private void initAsdFiles(){ try{ asdFiles = new
    * ArrayList<IFile>(); filesInAsd = new ArrayList<ArrayList<String>>(); for(
    * IResource resource : getProject().members() ){ if(resource.getType() ==
    * IResource.FILE){ IFile file = (IFile)resource;
    * if(file.getFileExtension().equalsIgnoreCase("asd")){ asdFiles.add(file);
    * filesInAsd.add(getFilesInAsd(file)); } } } return; } catch (CoreException
    * e) { return; } } private ArrayList<String> getFilesInAsd(IFile asdFile){
    * ArrayList<String> res = new ArrayList<String>(); LispNode code =
    * LispParser.parse(asdFile); for( LispNode node: code.params ){ if(
    * node.isCarEqual("defsystem") ){ for( LispNode subnode :
    * node.getf(":components").params ) { if( subnode.isCarEqual(":file")){
    * res.add(subnode.cadr().value); //TODO: cannot handle files in subfolders }
    * } } } return res; } //TODO: assumes that file is in same folder as asd
    * private IFile getAsdForFile(IFile file){ if( asdFiles == null || file ==
    * null){ return null; } else { String ext = file.getFileExtension(); if(
    * ext.equalsIgnoreCase("lisp") || ext.equalsIgnoreCase("cl")){ String name =
    * file.getName(); name = name.substring(0,
    * name.length()-1-(ext.length()+1)); for(int i = 0; i < asdFiles.size();
    * ++i){ for(String filename: filesInAsd.get(i)){
    * if(filename.equalsIgnoreCase(name)){ return asdFiles.get(i); } } } }
    * return null; } }
    */

   class LispDeltaVisitor implements IResourceDeltaVisitor {

      @Override
      public boolean visit(final IResourceDelta delta) throws CoreException {
         if ( !LispPlugin.getDefault()
                         .getPreferenceStore()
                         .getString(PreferenceConstants.BUILD_TYPE)
                         .equals(PreferenceConstants.USE_ECLIPSE_BUILD)) {
            return true;
         }
         final IResource resource = delta.getResource();
         if (resource instanceof IFile) {
            final IFile file = (IFile) resource;
            switch (delta.getKind()) {
               case IResourceDelta.ADDED:
                  // handle added resource
                  if (LispBuilder.checkLisp(file)) {
                     LispBuilder.compileFile(file, false);
                  }
                  break;
               case IResourceDelta.REMOVED:
                  // handle removed resource - TODO: undefine all functions in
                  // file before file is removed?
                  break;
               case IResourceDelta.CHANGED:
                  // handle changed resource
                  if (LispBuilder.checkLisp(file)) {
                     LispBuilder.compileFile(file, false);
                  }
                  break;
            }
         }
         // return true to continue visiting children.
         return true;
      }
   }

   class LispResourceVisitor implements IResourceVisitor {
      @Override
      public boolean visit(final IResource resource) {
         if (resource.isAccessible()) {
            if (LispPlugin.getDefault()
                          .getPreferenceStore()
                          .getString(PreferenceConstants.BUILD_TYPE)
                          .equals(PreferenceConstants.USE_ECLIPSE_BUILD) &&
                     ((resource instanceof IFile) && LispBuilder.checkLisp((IFile) resource))) {
               LispBuilder.compileFile((IFile) resource, false);
            }
            // return true to continue visiting children.
            return true;
         }
         else {
            return false;
         }
      }
   }

   public static final String BUILDER_ID = "jasko.tim.lisp.lispBuilder";

   public static boolean checkLisp(final IFile resource) {
      if ( !(resource.getName().endsWith(".lisp") ||
               resource.getName().endsWith(".el") || resource.getName()
                                                             .endsWith(".cl"))) {
         return false;
      }
      else {

         try {
            final IFile file = resource;
            LispMarkers.deleteCompileMarkers(file);
            System.out.println("*builder*");
            final boolean paren = LispBuilder.checkParenBalancing(file);
            final LispNode code = LispParser.parse(file);
            final boolean pack = LispBuilder.checkPackageDependence(code, file);
            final boolean commas = LispBuilder.checkCommas(code, file);
            LispBuilder.checkMultipleDefuncs(code, file);
            return (paren && pack && commas);
         }
         catch (final Exception e) {
            e.printStackTrace();
            return false;
         }
      }
   }

   public static boolean checkLisp(final IFile resource,
                                   final int offset,
                                   final int length) {
      if ( !(resource.getName().endsWith(".lisp") ||
               resource.getName().endsWith(".el") || resource.getName()
                                                             .endsWith(".cl"))) {
         return false;
      }
      else {

         try {
            final IFile file = resource;
            LispMarkers.deleteCompileMarkers(file, offset, length);
            System.out.println("*builder*");
            final boolean paren = LispBuilder.checkParenBalancing(file);
            final LispNode code = LispParser.parse(file);
            final boolean pack = LispBuilder.checkPackageDependence(code, file);
            final boolean commas = LispBuilder.checkCommas(code, file);
            LispBuilder.checkMultipleDefuncs(code, file);
            return (paren && pack && commas);
         }
         catch (final Exception e) {
            e.printStackTrace();
            return false;
         }
      }
   }

   public static void compileFile(final IFile file, final boolean makeFasl) {
      if (file == null) {
         return;
      }
      if (makeFasl) {
         try {
            final SwankInterface swank = LispPlugin.getDefault().getSwank();
            swank.sendCompileFile(file.getLocation().toString(),
                                  new CompileListener(file, true));
            System.out.printf("Compiling %s\n", file.getLocation().toString());
         }
         catch (final Exception e) {
            e.printStackTrace();
         }
      }
      else {
         LispBuilder.compileFilePart(file, LispParser.fileToString(file), 0);
      }
   }

   public static void compileFilePart(final IFile file,
                                      final String expr,
                                      final int offset) {
      if ((expr == null) || (expr == "")) {
         return;
      }
      try {
         final SwankInterface swank = LispPlugin.getDefault().getSwank();
         final String filename = file.getName();
         String dir = file.getLocation().toPortableString();
         dir = dir.substring(0, dir.length() - filename.length());
         final String pkg = LispUtil.getPackage(LispParser.fileToString(file),
                                                offset);
         swank.sendCompileString(expr,
                                 filename,
                                 dir,
                                 offset,
                                 pkg,
                                 new CompileListener(file,
                                                     offset,
                                                     expr.length()));

      }
      catch (final Exception e) {
         e.printStackTrace();
      }
   }

   private static boolean checkCommas(final LispNode code, final IFile file) {
      boolean res = true;
      if (code.value.equals(",") && !code.isString) {
         LispMarkers.addCommaMarker(file, code.offset, code.line);
         res = false;
      }
      boolean inBackQuote = false;
      for (final LispNode node : code.params) {
         if (node.value.equals("`")) {
            inBackQuote = true;
         }
         else {
            if ( !inBackQuote) {
               res = res && LispBuilder.checkCommas(node, file);
            }
            inBackQuote = false;
         }
      }
      return res;
   }

   private static void checkMultipleDefuncs(final LispNode code,
                                            final IFile file) {
      if (file == null) {
         return;
      }
      final ArrayList<TopLevelItem> items = LispUtil.getTopLevelItems(code, "");
      // find multiple forms with same name and package,
      final HashMap<String, ArrayList<TopLevelItem>> multItems = new HashMap<String, ArrayList<TopLevelItem>>();
      // add all forms to hashtable
      for (final TopLevelItem itm : items) {
         final String itmTmp = itm.type + "," + itm.name + "," + itm.pkg;
         if (itm.type.equalsIgnoreCase("in-package")) {

         }
         else
            if (multItems.containsKey(itmTmp)) {// duplicate items of modified
                                                // forms
               multItems.get(itmTmp).add(itm);
            }
            else {
               final ArrayList<TopLevelItem> lst = new ArrayList<TopLevelItem>();
               lst.add(itm);
               multItems.put(itmTmp, lst);
            }
      }
      // process keys that have more than one entry
      for (final ArrayList<TopLevelItem> lst : multItems.values()) {
         if ((lst.size() > 1) && lst.get(0).type.equalsIgnoreCase("defun")) {
            for (int i = 0; i < (lst.size() - 1); ++i) {
               final int offset = lst.get(i).offset + "defun".length() + 2;
               final int offsetEnd = offset + lst.get(i).name.length();
               LispMarkers.addMultMarker(file, offset, offsetEnd, false);
            }
            final int i = lst.size() - 1;
            final int offset = lst.get(i).offset + "defun".length() + 2;
            final int offsetEnd = offset + lst.get(i).name.length();
            LispMarkers.addMultMarker(file, offset, offsetEnd, true);
         }
      }

   }

   private static boolean checkPackageDependence(final LispNode code,
                                                 final IFile file) {
      if (file == null) {
         return false;
      }
      try {
         boolean cancompile = true;
         // check if package dependence is satisfied:
         final ArrayList<String> pkgs = LispPlugin.getDefault()
                                                  .getSwank()
                                                  .getAvailablePackages(3000);
         for (final LispNode node : code.params) {
            final String nodetype = node.car().value.toLowerCase();
            // == defpackage
            if (nodetype.equals("defpackage")) {
               pkgs.add(LispUtil.formatPackage(node.cadr().value));
               for (final LispNode subnode : node.params) {
                  final String subtype = subnode.car().value.toLowerCase();
                  // process nicknames
                  if (subtype.equals(":nicknames")) {
                     for (int i = 1; i < subnode.params.size(); ++i) {
                        pkgs.add(LispUtil.formatPackage(subnode.get(i).value));
                     }
                     // process :use
                  }
                  else
                     if (subtype.equals(":use")) {
                        for (int i = 1; i < subnode.params.size(); ++i) {
                           final LispNode usepkg = subnode.params.get(i);
                           if ( !pkgs.contains(LispUtil.formatPackage(usepkg.value))) {
                              LispMarkers.addPackageMarker(file,
                                                           usepkg.offset,
                                                           usepkg.endOffset + 1,
                                                           usepkg.line,
                                                           LispUtil.formatPackage(usepkg.value));
                              cancompile = false;
                           }
                        }
                        // process :shadowing-import-from and :import-from
                        // dependence
                     }
                     else
                        if (subtype.equals(":shadowing-import-from") ||
                                 subtype.equals(":import-from")) {
                           final LispNode usepkg = subnode.cadr();
                           if ( !pkgs.contains(LispUtil.formatPackage(usepkg.value))) {
                              if (usepkg.offset == 0) {
                                 LispMarkers.addPackageMarker(file,
                                                              subnode.offset,
                                                              subnode.offset +
                                                                       subtype.length(),
                                                              subnode.line,
                                                              LispUtil.formatPackage(usepkg.value));
                              }
                              else {
                                 LispMarkers.addPackageMarker(file,
                                                              usepkg.offset,
                                                              usepkg.endOffset,
                                                              usepkg.line,
                                                              LispUtil.formatPackage(usepkg.value));
                              }
                              cancompile = false;
                           }
                        }
               }
               // == make-package
            }
            else
               if (nodetype.equals("make-package")) {
                  pkgs.add(LispUtil.formatPackage(node.cadr().value));
                  final LispNode nicks = node.getf(":nicknames");
                  if (nicks != null) {
                     for (final LispNode nick : nicks.params) {
                        pkgs.add(LispUtil.formatPackage(nick.value));
                     }
                  }
                  final LispNode uses = node.getf(":use");
                  if (uses != null) {
                     for (final LispNode usepkg : uses.params) {
                        if ( !pkgs.contains(LispUtil.formatPackage(usepkg.value))) {
                           if (usepkg.offset == 0) {
                              LispMarkers.addPackageMarker(file,
                                                           node.offset,
                                                           node.offset +
                                                                    nodetype.length(),
                                                           node.line,
                                                           LispUtil.formatPackage(usepkg.value));
                           }
                           else {
                              LispMarkers.addPackageMarker(file,
                                                           usepkg.offset,
                                                           usepkg.endOffset,
                                                           usepkg.line,
                                                           LispUtil.formatPackage(usepkg.value));
                           }
                           cancompile = false;
                        }
                     }
                  }
               }
               else
                  if (nodetype.equals("in-package")) {
                     final LispNode pkgnode = node.cadr();
                     if ( !pkgs.contains(LispUtil.formatPackage(pkgnode.value))) {
                        if (pkgnode.offset == 0) {
                           LispMarkers.addPackageMarker(file,
                                                        node.offset,
                                                        node.offset +
                                                                 nodetype.length(),
                                                        node.line,
                                                        LispUtil.formatPackage(pkgnode.value));
                        }
                        else {
                           LispMarkers.addPackageMarker(file,
                                                        pkgnode.offset,
                                                        pkgnode.endOffset,
                                                        pkgnode.line,
                                                        LispUtil.formatPackage(pkgnode.value));
                        }
                        cancompile = false;
                     }
                  }
         }

         return cancompile;
      }
      catch (final Exception e) {
         e.printStackTrace();
         return false;
      }
   }

   private static boolean checkParenBalancing(final IFile file) {
      if ( !(file.getFileExtension().equalsIgnoreCase("lisp") ||
               file.getFileExtension().equalsIgnoreCase("el") || file.getFileExtension()
                                                                     .equalsIgnoreCase("cl"))) {
         return false;
      }
      else {
         try {
            boolean res = true;
            int open = 0;
            int close = 0;
            int lineNum = 1;
            int charOffset = 0;

            boolean inQuotes = false;
            boolean inComment = false;
            // each parenData is an array of 3 numbers:
            // paren type: -1 close, 1 open
            // offset
            // lineNum
            final ArrayList<int[]> parenData = new ArrayList<int[]>();

            final BufferedReader reader = new BufferedReader(new InputStreamReader(file.getContents()));
            String line = reader.readLine();
            while (line != null) {
               for (int i = 0; i < line.length(); ++i) {
                  if (inComment) {
                     final int endComment = line.indexOf("|#", i);
                     if (endComment >= 0) {
                        charOffset += endComment - i;
                        i = endComment;
                        inComment = false;
                     }
                     else {
                        charOffset += line.length() - i;
                        i = line.length();
                        break;
                     }
                  }
                  final char c = line.charAt(i);
                  if ((c == '(') &&
                           !inQuotes &&
                           !((i > 1) && (line.charAt(i - 1) == '\\') && (line.charAt(i - 2) == '#'))) {
                     ++open;
                     parenData.add(new int[] { 1, charOffset, lineNum });
                  }
                  else
                     if ((c == ')') &&
                              !inQuotes &&
                              !((i > 1) && (line.charAt(i - 1) == '\\') && (line.charAt(i - 2) == '#'))) {
                        ++close;
                        parenData.add(new int[] { -1, charOffset, lineNum });
                        if (close > open) {
                           // reset everything so we don't throw off the rest of
                           // the matching
                           close = open;
                           res = false;
                           LispMarkers.addParenMarker(file,
                                                      charOffset,
                                                      lineNum,
                                                      false);
                        } // if
                     }
                     else
                        if ((c == ';') &&
                                 !inQuotes &&
                                 !((i > 1) && (line.charAt(i - 1) == '\\') && (line.charAt(i - 2) == '#'))) {
                           charOffset += line.length() - i;
                           i = line.length();
                           break;
                        }
                        else
                           if ((c == '"') &&
                                    !(((i > 1) && inQuotes && (line.charAt(i - 1) == '\\')) || ((i > 2) && ( !inQuotes && ((line.charAt(i - 1) == '\\') && (line.charAt(i - 2) == '#')))))) {
                              inQuotes = !inQuotes;
                           }
                           else
                              if ((c == '#') &&
                                       !((i > 1) && ((line.charAt(i - 1) == '\\') || (line.charAt(i - 2) == '#')))) {
                                 if ((i + 1) < line.length()) {
                                    if (line.charAt(i + 1) == '|') {
                                       inComment = true;
                                    }
                                 }
                              }
                  ++charOffset;
               } // for i

               ++lineNum;
               ++charOffset;
               line = reader.readLine();
            }
            if (open > close) { // go backwards
               open = 0;
               close = 0;
               for (int k = parenData.size() - 1; k >= 0; --k) {
                  if (parenData.get(k)[0] == -1) {// close
                     ++close;
                  }
                  else {
                     ++open;
                     if (open > close) {
                        // reset everything so we don't throw off the rest of
                        // the matching
                        open = close;
                        LispMarkers.addParenMarker(file,
                                                   parenData.get(k)[1],
                                                   parenData.get(k)[2],
                                                   true);
                     }
                  }
               } // for k
               res = false;
            }
            return res;
         }
         catch (final Exception e) {
            e.printStackTrace();
            return false;
         }
      }
   }

   @Override
   @SuppressWarnings("unchecked")
   protected IProject[] build(final int kind,
                              final Map args,
                              final IProgressMonitor monitor)
            throws CoreException {
      if (kind == IncrementalProjectBuilder.FULL_BUILD) {
         fullBuild(monitor);
      }
      else {
         final IResourceDelta delta = getDelta(getProject());
         if (delta == null) {
            fullBuild(monitor);
         }
         else {
            incrementalBuild(delta, monitor);
         }
      }
      return null;
   }

   protected void fullBuild(final IProgressMonitor monitor)
            throws CoreException {
      try {
         getProject().accept(new LispResourceVisitor());
      }
      catch (final CoreException e) {
      }
   }

   protected void incrementalBuild(final IResourceDelta delta,
                                   final IProgressMonitor monitor)
            throws CoreException {
      // the visitor does the work.
      delta.accept(new LispDeltaVisitor());
   }
}
