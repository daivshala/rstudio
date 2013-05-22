/*
 * EnvironmentPresenter.java
 *
 * Copyright (C) 2009-12 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */

/* Some examples:
 * 
 * Adding an RPC method
 * https://github.com/rstudio/rstudio/commit/6815944da3e140aa1064a0c9866db7a70731c9d0
 * 
 * Presenter -> Model/View 
 * https://github.com/rstudio/rstudio/commit/0b7ef94ec6d9ad8c0a9385d2d1a8b43edf280f52
 * 
 * Adding a Command
 * https://github.com/rstudio/rstudio/commit/a5eee4b211dc09eac2221a9f825cfcfc3221f144
 * 
 * Raising Events from the Server
 * https://github.com/rstudio/rstudio/commit/6178166bf1c97a338986a85e5694f7278c0bc940
 * 
 */

package org.rstudio.studio.client.workbench.views.environment;

import org.rstudio.core.client.StringUtil;
import org.rstudio.core.client.command.CommandBinder;
import org.rstudio.core.client.command.Handler;
import org.rstudio.core.client.files.FileSystemItem;
import org.rstudio.core.client.js.JsObject;
import org.rstudio.core.client.widget.OperationWithInput;
import org.rstudio.core.client.widget.ProgressIndicator;
import org.rstudio.core.client.widget.ProgressOperation;
import org.rstudio.core.client.widget.ProgressOperationWithInput;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.common.ConsoleDispatcher;
import org.rstudio.studio.client.common.FileDialogs;
import org.rstudio.studio.client.common.GlobalDisplay;
import org.rstudio.studio.client.common.filetypes.events.OpenDataFileEvent;
import org.rstudio.studio.client.common.filetypes.events.OpenDataFileHandler;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.server.ServerRequestCallback;
import org.rstudio.studio.client.server.VoidServerRequestCallback;
import org.rstudio.studio.client.workbench.WorkbenchContext;
import org.rstudio.studio.client.workbench.WorkbenchView;
import org.rstudio.studio.client.workbench.commands.Commands;
import org.rstudio.studio.client.workbench.model.ClientState;
import org.rstudio.studio.client.workbench.model.RemoteFileSystemContext;
import org.rstudio.studio.client.workbench.model.Session;
import org.rstudio.studio.client.workbench.model.helper.JSObjectStateValue;
import org.rstudio.studio.client.workbench.views.BasePresenter;
import org.rstudio.studio.client.workbench.views.console.events.SendToConsoleEvent;
import org.rstudio.studio.client.workbench.views.environment.events.ContextDepthChangedEvent;
import org.rstudio.studio.client.workbench.views.environment.events.EnvironmentObjectAssignedEvent;
import org.rstudio.studio.client.workbench.views.environment.events.EnvironmentObjectRemovedEvent;
import org.rstudio.studio.client.workbench.views.environment.events.EnvironmentRefreshEvent;
import org.rstudio.studio.client.workbench.views.environment.model.EnvironmentServerOperations;
import org.rstudio.studio.client.workbench.views.environment.model.EnvironmentState;
import org.rstudio.studio.client.workbench.views.environment.model.RObject;


import com.google.gwt.core.client.JsArray;
import com.google.inject.Inject;
import org.rstudio.studio.client.workbench.views.environment.dataimport.ImportFileSettings;
import org.rstudio.studio.client.workbench.views.environment.dataimport.ImportFileSettingsDialog;
import org.rstudio.studio.client.workbench.views.environment.model.DownloadInfo;
import org.rstudio.studio.client.workbench.views.environment.view.EnvironmentClientState;

import java.util.HashMap;

public class EnvironmentPresenter extends BasePresenter
        implements OpenDataFileHandler

{
   public interface Binder extends CommandBinder<Commands, EnvironmentPresenter> {}
   
   public interface Display extends WorkbenchView
   {
      void addObject(RObject object);
      void addObjects(JsArray<RObject> objects);
      void clearObjects();
      void setContextDepth(int contextDepth);
      void removeObject(String object);
      void setEnvironmentName(String name);
   }
   
   @Inject
   public EnvironmentPresenter(Display view,
                               EnvironmentServerOperations server,
                               Binder binder,
                               Commands commands,
                               GlobalDisplay globalDisplay,
                               EventBus eventBus,
                               FileDialogs fileDialogs,
                               WorkbenchContext workbenchContext,
                               ConsoleDispatcher consoleDispatcher,
                               RemoteFileSystemContext fsContext,
                               Session session)
   {
      super(view);
      binder.bind(commands, this);
      
      view_ = view;
      server_ = server;
      globalDisplay_ = globalDisplay;
      consoleDispatcher_ = consoleDispatcher;
      fsContext_ = fsContext;
      fileDialogs_ = fileDialogs;
      workbenchContext_ = workbenchContext;
      eventBus_ = eventBus;

      refreshView();
      
      eventBus.addHandler(EnvironmentRefreshEvent.TYPE, 
                          new EnvironmentRefreshEvent.Handler()
      {
         @Override
         public void onEnvironmentRefresh(EnvironmentRefreshEvent event)
         {
            refreshView();
         }
      });
      
      eventBus.addHandler(ContextDepthChangedEvent.TYPE, 
                          new ContextDepthChangedEvent.Handler()
      {
         @Override
         public void onContextDepthChanged(ContextDepthChangedEvent event)
         {
            contextDepth_ = event.getContextDepth();
            view_.setContextDepth(contextDepth_);
            view_.setEnvironmentName(event.getFunctionName());
            setViewFromEnvironmentList(event.getEnvironmentList());
         }
      });
      
      eventBus.addHandler(EnvironmentObjectAssignedEvent.TYPE,
                          new EnvironmentObjectAssignedEvent.Handler() 
      {
         @Override
         public void onEnvironmentObjectAssigned(EnvironmentObjectAssignedEvent event)
         {
            view_.addObject(event.getObjectInfo());
         }
      });

      eventBus.addHandler(EnvironmentObjectRemovedEvent.TYPE,
            new EnvironmentObjectRemovedEvent.Handler() 
      {
         @Override
         public void onEnvironmentObjectRemoved(EnvironmentObjectRemovedEvent event)
         {
            view_.removeObject(event.getObjectName());
         }
      });

      new JSObjectStateValue("environment-pane", "environmentClientState", ClientState.TEMPORARY,
                             session.getSessionInfo().getClientState(), false)
      {
         @Override
         protected void onInit(JsObject value)
         {
            if (value != null)
               clientState_ = value.cast();
            lastKnownState_ = clientState_;
         }

         @Override
         protected JsObject getValue()
         {
            return clientState_.cast();
         }

         @Override
         protected boolean hasChanged()
         {
            if (!EnvironmentClientState.areEqual(lastKnownState_, clientState_))
            {
               lastKnownState_ = clientState_;
               return true;
            }

            return false;
         }

         private EnvironmentClientState lastKnownState_;
      };

   }

   @Handler
   void onRefreshEnvironment()
   {
      refreshView();
   }

   void onClearWorkspace()
   {
      view_.bringToFront();

      new ClearAllDialog(new ProgressOperationWithInput<Boolean>() {

         @Override
         public void execute(Boolean includeHidden, ProgressIndicator indicator)
         {
            indicator.onProgress("Removing objects...");
            server_.removeAllObjects(
                    includeHidden,
                    new VoidServerRequestCallback(indicator) {
                        @Override
                        public void onSuccess()
                        {
                           view_.clearObjects();
                        }
                    });
         }
      }).showModal();
   }

   void onSaveWorkspace()
   {
      view_.bringToFront();

      consoleDispatcher_.saveFileAsThenExecuteCommand("Save Workspace As",
                                                      ".RData",
                                                      true,
                                                      "save.image");
   }

   void onLoadWorkspace()
   {
      view_.bringToFront();
      consoleDispatcher_.chooseFileThenExecuteCommand("Load Workspace", "load");
   }

   void onImportDatasetFromFile()
   {
      view_.bringToFront();
      fileDialogs_.openFile(
              "Select File to Import",
              fsContext_,
              workbenchContext_.getCurrentWorkingDir(),
              new ProgressOperationWithInput<FileSystemItem>()
              {
                 public void execute(
                         FileSystemItem input,
                         ProgressIndicator indicator)
                 {
                    if (input == null)
                       return;

                    indicator.onCompleted();

                    showImportFileDialog(input, null);
                 }
              });
   }

   void onImportDatasetFromURL()
   {
      view_.bringToFront();
      globalDisplay_.promptForText(
              "Import from Web URL" ,
              "Please enter the URL to import data from:",
              "",
              new ProgressOperationWithInput<String>(){
                 public void execute(String input, final ProgressIndicator indicator)
                 {
                    indicator.onProgress("Downloading data...");
                    server_.downloadDataFile(input.trim(),
                        new ServerRequestCallback<DownloadInfo>(){

                           @Override
                           public void onResponseReceived(DownloadInfo downloadInfo)
                           {
                              indicator.onCompleted();
                              showImportFileDialog(
                                FileSystemItem.createFile(downloadInfo.getPath()),
                                downloadInfo.getVarname());
                           }

                           @Override
                           public void onError(ServerError error)
                           {
                              indicator.onError(error.getUserMessage());
                           }

                        });
                 }
              });
   }

   public void onOpenDataFile(OpenDataFileEvent event)
   {
      final String dataFilePath = event.getFile().getPath();
      globalDisplay_.showYesNoMessage(GlobalDisplay.MSG_QUESTION,
           "Confirm Load Workspace",

           "Do you want to load the R data file \"" + dataFilePath + "\" " +
           "into your workspace?",

           new ProgressOperation() {
              public void execute(ProgressIndicator indicator)
              {
                 consoleDispatcher_.executeCommand(
                         "load",
                         FileSystemItem.createFile(dataFilePath));

                 indicator.onCompleted();
              }
           },

           true);
   }

   @Override
   public void onBeforeSelected()
   {
      super.onBeforeSelected();
      refreshView();
   }

   public void initialize(EnvironmentState environmentState)
   {
      environmentState_ = environmentState;
      setContextDepth(environmentState_.contextDepth());
   }
   
   public void setContextDepth(int contextDepth)
   {
      contextDepth_ = contextDepth;
      view_.setContextDepth(contextDepth_);
   }

   private void setViewFromEnvironmentList(JsArray<RObject> objects)
   {
      view_.clearObjects();
      view_.addObjects(objects);
   }
    
   private void refreshView()
   {
      view_.setProgress(true);
      server_.listEnvironment(new ServerRequestCallback<JsArray<RObject>>()
      {

         @Override
         public void onResponseReceived(JsArray<RObject> objects)
         {
            setViewFromEnvironmentList(objects);
            view_.setProgress(false);
         }

         @Override
         public void onError(ServerError error)
         {
            globalDisplay_.showErrorMessage("Error Listing Objects",
                                            error.getUserMessage());
            view_.setProgress(false);
         }
      });
   }

   private void showImportFileDialog(FileSystemItem input, String varname)
   {
      ImportFileSettingsDialog dialog = new ImportFileSettingsDialog(
              server_,
              input,
              varname,
              "Import Dataset",
              new OperationWithInput<ImportFileSettings>()
              {
                 public void execute(
                         ImportFileSettings input)
                 {
                    String var = StringUtil.toRSymbolName(input.getVarname());
                    String code =
                            var +
                            " <- " +
                            makeCommand(input) +
                            "\n  View(" + var + ")";
                    eventBus_.fireEvent(new SendToConsoleEvent(code, true));
                 }
              },
              globalDisplay_);
      dialog.showModal();
   }

   private String makeCommand(ImportFileSettings input)
   {
      HashMap<String, ImportFileSettings> commandDefaults_ =
              new HashMap<String, ImportFileSettings>();

      commandDefaults_.put("read.table", new ImportFileSettings(
              null, null, false, "", ".", "\"'"));
      commandDefaults_.put("read.csv", new ImportFileSettings(
              null, null, true, ",", ".", "\""));
      commandDefaults_.put("read.delim", new ImportFileSettings(
              null, null, true, "\t", ".", "\""));
      commandDefaults_.put("read.csv2", new ImportFileSettings(
              null, null, true, ";", ",", "\""));
      commandDefaults_.put("read.delim2", new ImportFileSettings(
              null, null, true, "\t", ",", "\""));

      String command = "read.table";
      ImportFileSettings settings = commandDefaults_.get("read.table");
      int score = settings.calculateSimilarity(input);
      for (String cmd : new String[] {"read.csv", "read.delim"})
      {
         ImportFileSettings theseSettings = commandDefaults_.get(cmd);
         int thisScore = theseSettings.calculateSimilarity(input);
         if (thisScore > score)
         {
            score = thisScore;
            command = cmd;
            settings = theseSettings;
         }
      }

      StringBuilder code = new StringBuilder(command);
      code.append("(");
      code.append(StringUtil.textToRLiteral(input.getFile().getPath()));
      if (input.isHeader() != settings.isHeader())
         code.append(", header=" + (input.isHeader() ? "T" : "F"));
      if (!input.getSep().equals(settings.getSep()))
         code.append(", sep=" + StringUtil.textToRLiteral(input.getSep()));
      if (!input.getDec().equals(settings.getDec()))
         code.append(", dec=" + StringUtil.textToRLiteral(input.getDec()));
      if (!input.getQuote().equals(settings.getQuote()))
         code.append(", quote=" + StringUtil.textToRLiteral(input.getQuote()));
      code.append(")");

      return code.toString();
   }

   private final Display view_;
   private final EnvironmentServerOperations server_;
   private final GlobalDisplay globalDisplay_;
   private final ConsoleDispatcher consoleDispatcher_;
   private final RemoteFileSystemContext fsContext_;
   private final WorkbenchContext workbenchContext_;
   private final FileDialogs fileDialogs_;
   private final EventBus eventBus_;
   private EnvironmentState environmentState_;
   private int contextDepth_;
   private EnvironmentClientState clientState_ =
           EnvironmentClientState.create(0);
}
