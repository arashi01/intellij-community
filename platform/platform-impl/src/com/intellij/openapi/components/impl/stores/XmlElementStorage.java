/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.components.impl.stores;

import com.intellij.ide.plugins.IdeaPluginDescriptorImpl;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.openapi.vfs.SafeWriteRequestor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.io.fs.IFile;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import gnu.trove.TObjectLongHashMap;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.filter.ElementFilter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;

public abstract class XmlElementStorage implements StateStorage, Disposable {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.components.impl.stores.XmlElementStorage");

  private static final String ATTR_NAME = "name";
  private static final String VERSION_FILE_SUFFIX = ".ver";

  protected TrackingPathMacroSubstitutor myPathMacroSubstitutor;
  @NotNull private final String myRootElementName;
  private Object mySession;
  private StorageData myLoadedData;
  protected final StreamProvider myStreamProvider;
  protected final String myFileSpec;
  private final ComponentRoamingManager myComponentRoamingManager;
  protected boolean myBlockSavingTheContent = false;
  protected int myUpToDateHash = -1;
  protected int myProviderUpToDateHash = -1;
  private boolean mySavingDisabled = false;

  private final Map<String, Object> myStorageComponentStates = new THashMap<String, Object>(); // at load we store Element, on setState Integer of hash

  private final ComponentVersionProvider myLocalVersionProvider;
  protected final RemoteComponentVersionProvider myRemoteVersionProvider;

  protected ComponentVersionListener myListener = new ComponentVersionListener(){
    @Override
    public void componentStateChanged(String componentName) {
      myLocalVersionProvider.changeVersion(componentName,  System.currentTimeMillis());
    }
  };

  private boolean myDisposed;

  protected XmlElementStorage(@Nullable TrackingPathMacroSubstitutor pathMacroSubstitutor,
                              @NotNull Disposable parentDisposable,
                              @NotNull String rootElementName,
                              @Nullable StreamProvider streamProvider,
                              String fileSpec,
                              ComponentRoamingManager componentRoamingManager,
                              ComponentVersionProvider componentVersionProvider) {
    myPathMacroSubstitutor = pathMacroSubstitutor;
    myRootElementName = rootElementName;
    myStreamProvider = streamProvider;
    myFileSpec = fileSpec;
    myComponentRoamingManager = componentRoamingManager;
    Disposer.register(parentDisposable, this);

    myLocalVersionProvider = componentVersionProvider;
    myRemoteVersionProvider = streamProvider == null || !streamProvider.isVersioningRequired() ? null : new RemoteComponentVersionProvider();
  }

  protected boolean isDisposed() {
    return myDisposed;
  }

  @Nullable
  protected abstract Element loadDocument();

  @Nullable
  public synchronized Element getState(@NotNull String componentName) {
    final StorageData storageData = getStorageData(false, componentName);
    final Element state = storageData.getState(componentName);
    if (state != null) {
      if (!myStorageComponentStates.containsKey(componentName)) {
        myStorageComponentStates.put(componentName, state);
      }
      storageData.removeState(componentName);
    }
    return state;
  }

  @Override
  public boolean hasState(final Object component, @NotNull String componentName, final Class<?> aClass, final boolean reloadData) throws StateStorageException {
    return getStorageData(reloadData, componentName).hasState(componentName);
  }

  @Override
  @Nullable
  public <T> T getState(final Object component, @NotNull String componentName, Class<T> stateClass, @Nullable T mergeInto) throws StateStorageException {
    return DefaultStateSerializer.deserializeState(getState(componentName), stateClass, mergeInto);
  }

  @NotNull
  protected StorageData getStorageData() {
    return getStorageData(false, null);
  }

  @NotNull
  private StorageData getStorageData(boolean reloadData, @Nullable String componentName) {
    if (myLoadedData != null && !reloadData) {
      return myLoadedData;
    }

    myLoadedData = loadData(true, myFileSpec.equals(StoragePathMacros.WORKSPACE_FILE) ? null : myComponentRoamingManager.getRoamingType(componentName));
    return myLoadedData;
  }

  @NotNull
  protected StorageData loadData(boolean useProvidersData, @Nullable RoamingType roamingType) {
    Element element = loadDocument();
    StorageData result = createStorageData();

    if (element != null) {
      loadState(result, element);
    }

    if (useProvidersData && myStreamProvider != null && myStreamProvider.isEnabled()) {
      if (roamingType == null) {
        loadDataFromStreamProvider(result, RoamingType.PER_USER);
        loadDataFromStreamProvider(result, RoamingType.PER_PLATFORM);
      }
      else if (roamingType != RoamingType.DISABLED) {
        loadDataFromStreamProvider(result, roamingType);
      }
    }
    return result;
  }

  private void loadDataFromStreamProvider(@NotNull StorageData result, @NotNull RoamingType roamingType) {
    assert myStreamProvider != null;
    try {
      Document sharedDocument = StorageUtil.loadDocument(myStreamProvider.loadContent(myFileSpec, roamingType));
      if (sharedDocument != null) {
        filterOutOfDate(sharedDocument.getRootElement());
        loadState(result, sharedDocument.getRootElement());
      }
    }
    catch (Exception e) {
      LOG.warn(e);
    }
  }

  protected void loadState(@NotNull StorageData result, @NotNull Element element) {
    if (myPathMacroSubstitutor != null) {
      myPathMacroSubstitutor.expandPaths(element);
    }

    IdeaPluginDescriptorImpl.internJDOMElement(element);

    result.load(element);
    result.checkUnknownMacros(myPathMacroSubstitutor);
  }

  @NotNull
  protected StorageData createStorageData() {
    return new StorageData(myRootElementName);
  }

  public void setDefaultState(final Element element) {
    myLoadedData = createStorageData();
    loadState(myLoadedData, element);
  }

  @Override
  @NotNull
  public ExternalizationSession startExternalization() {
    ExternalizationSession session = new MyExternalizationSession(getStorageData().clone(), myListener);
    mySession = session;
    return session;
  }

  @Override
  @NotNull
  public SaveSession startSave(@NotNull final ExternalizationSession externalizationSession) {
    LOG.assertTrue(mySession == externalizationSession);

    final SaveSession saveSession = mySavingDisabled ? createNullSession() : createSaveSession((MyExternalizationSession)externalizationSession);
    mySession = saveSession;
    return saveSession;
  }

  private static SaveSession createNullSession() {
    return new SaveSession(){
      @Override
      public void save() throws StateStorageException {
      }

      @Override
      public Set<String> analyzeExternalChanges(@NotNull final Set<Pair<VirtualFile, StateStorage>> changedFiles) {
        return Collections.emptySet();
      }

      @NotNull
      @Override
      public Collection<IFile> getStorageFilesToSave() throws StateStorageException {
        return Collections.emptySet();
      }

      @NotNull
      @Override
      public List<IFile> getAllStorageFiles() {
        return Collections.emptyList();
      }
    };
  }

  protected abstract MySaveSession createSaveSession(MyExternalizationSession externalizationSession);

  @Override
  public void finishSave(@NotNull final SaveSession saveSession) {
    try {
      if (mySession != saveSession) {
        LOG.error("mySession=" + mySession + " saveSession=" + saveSession);
      }
    } finally {
      mySession = null;
    }
  }

  public void disableSaving() {
    mySavingDisabled = true;
  }

  protected class MyExternalizationSession implements ExternalizationSession {
    private final StorageData myStorageData;
    private final ComponentVersionListener myListener;

    public MyExternalizationSession(final StorageData storageData, ComponentVersionListener listener) {
      myStorageData = storageData;
      myListener = listener;
    }

    @Override
    public void setState(@NotNull Object component, @NotNull String componentName, @NotNull Object state, @Nullable Storage storageSpec) {
      assert mySession == this;

      Element element;
      try {
        element = DefaultStateSerializer.serializeState(state, storageSpec);
      }
      catch (WriteExternalException e) {
        LOG.debug(e);
        return;
      }

      if (element == null || JDOMUtil.isEmpty(element)) {
        return;
      }

      setState(componentName, element);
    }

    private synchronized void setState(@NotNull String componentName, @NotNull Element element)  {
      myStorageData.setState(componentName, element);
      int hash = JDOMUtil.getTreeHash(element);
      try {
        Object oldElementState = myStorageComponentStates.get(componentName);
        if (oldElementState instanceof Element && !JDOMUtil.areElementsEqual((Element)oldElementState, element) ||
            oldElementState instanceof Integer && hash != (Integer)oldElementState) {
          myListener.componentStateChanged(componentName);
        }
      }
      finally {
        myStorageComponentStates.put(componentName, hash);
      }
    }
  }

  @Nullable
  protected Element getElement(@NotNull StorageData data) {
    Element element = data.save();
    if (element == null || JDOMUtil.isEmpty(element)) {
      return null;
    }

    if (myPathMacroSubstitutor != null) {
      try {
        myPathMacroSubstitutor.collapsePaths(element);
      }
      finally {
        myPathMacroSubstitutor.reset();
      }
    }

    return element;
  }

  protected abstract class MySaveSession implements SaveSession, SafeWriteRequestor {
    final StorageData myStorageData;
    private Element myElementToSave;

    public MySaveSession(MyExternalizationSession externalizationSession) {
      myStorageData = externalizationSession.myStorageData;
    }

    public final boolean needsSave() {
      assert mySession == this;
      return _needsSave(calcHash());
    }

    private boolean _needsSave(int hash) {
      if (myBlockSavingTheContent) {
        return false;
      }

      if (myUpToDateHash == -1) {
        if (hash != -1) {
          if (!physicalContentNeedsSave()) {
            myUpToDateHash = hash;
            return false;
          }
          else {
            return true;
          }
        }
        else {
          return true;
        }
      }
      else if (hash != -1) {
        if (hash == myUpToDateHash) {
          return false;
        }
        if (!physicalContentNeedsSave()) {
          myUpToDateHash = hash;
          return false;
        }
        else {
          return true;
        }
      }
      else {
        return physicalContentNeedsSave();
      }
    }

    protected boolean physicalContentNeedsSave() {
      return true;
    }

    protected abstract void doSave() throws StateStorageException;

    protected int calcHash() {
      return -1;
    }

    @Override
    public final void save() throws StateStorageException {
      assert mySession == this;

      if (myBlockSavingTheContent) {
        return;
      }

      int hash = calcHash();
      try {
        if (myStreamProvider != null && myStreamProvider.isEnabled() && (myProviderUpToDateHash == -1 || myProviderUpToDateHash != hash)) {
          try {
            saveForProvider(myStreamProvider);
          }
          finally {
            myProviderUpToDateHash = hash;
          }
        }
      }
      finally {
        saveLocally(hash);
      }
    }

    private void saveLocally(final Integer hash) {
      try {
        if (!isHashUpToDate(hash) && _needsSave(hash)) {
          doSave();
        }
      }
      finally {
        myUpToDateHash = hash;
      }
    }

    private void saveForProvider(@NotNull StreamProvider streamProvider) {
      if (!streamProvider.isApplicable(myFileSpec, RoamingType.PER_USER)) {
        return;
      }

      Element element = getElementToSave();
      if (element == null || element.getChildren().isEmpty()) {
        streamProvider.delete(myFileSpec, RoamingType.PER_USER);
        streamProvider.delete(myFileSpec, RoamingType.PER_PLATFORM);
        return;
      }

      // skip the whole document if some component has disabled roaming type
      // you must not store components with different roaming types in one document
      // one exclusion: workspace file (you don't have choice in this case)
      // for example, it is important for ICS ProjectId - we cannot keep project in another place,
      // but this project id must not be shared
      if (!myFileSpec.equals(StoragePathMacros.WORKSPACE_FILE) &&
          element.getContent(new RoamingElementFilter(RoamingType.DISABLED)).iterator().hasNext()) {
        return;
      }

      RoamingElementFilter perPlatformFilter = new RoamingElementFilter(RoamingType.PER_PLATFORM);
      if (element.getContent(perPlatformFilter).iterator().hasNext()) {
        doSaveForProvider(element, new RoamingElementFilter(RoamingType.PER_USER));
        doSaveForProvider(element, perPlatformFilter);
      }
      else {
        doSaveForProvider(element, RoamingType.PER_USER, streamProvider);
      }
    }

    private void doSaveForProvider(Element element, RoamingElementFilter filter) {
      Element copiedElement = JDOMUtil.cloneElement(element, filter);
      if (copiedElement != null) {
        assert myStreamProvider != null;
        doSaveForProvider(copiedElement, filter.myRoamingType, myStreamProvider);
      }
    }

    private void doSaveForProvider(@NotNull Element element, @NotNull RoamingType roamingType, @NotNull StreamProvider streamProvider) {
      try {
        StorageUtil.doSendContent(streamProvider, myFileSpec, element, roamingType, true);
        if (streamProvider.isVersioningRequired()) {
          TObjectLongHashMap<String> versions = loadVersions(element.getChildren(StorageData.COMPONENT));
          if (!versions.isEmpty()) {
            Element versionDoc = StateStorageManagerImpl.createComponentVersionsXml(versions);
            StorageUtil.doSendContent(streamProvider, myFileSpec + VERSION_FILE_SUFFIX, versionDoc, roamingType, true);
          }
        }
      }
      catch (IOException e) {
        LOG.warn(e);
      }
    }

    private boolean isHashUpToDate(final Integer hash) {
      return myUpToDateHash != -1 && myUpToDateHash == hash;
    }

    @Nullable
    protected Element getElementToSave()  {
      if (myElementToSave == null) {
        myElementToSave = getElement(myStorageData);
      }
      return myElementToSave;
    }

    public StorageData getData() {
      return myStorageData;
    }

    @Override
    @Nullable
    public Set<String> analyzeExternalChanges(@NotNull final Set<Pair<VirtualFile,StateStorage>> changedFiles) {
      try {
        Element element = loadDocument();
        StorageData storageData = createStorageData();
        if (element == null) {
          return Collections.emptySet();
        }
        loadState(storageData, element);
        return storageData.getDifference(myStorageData, myPathMacroSubstitutor);
      }
      catch (StateStorageException e) {
        LOG.info(e);
      }

      return null;
    }

    private class RoamingElementFilter extends ElementFilter {
      final RoamingType myRoamingType;

      public RoamingElementFilter(RoamingType roamingType) {
        super(StorageData.COMPONENT);

        myRoamingType = roamingType;
      }

      @Override
      public boolean matches(Object obj) {
        return super.matches(obj) && myComponentRoamingManager.getRoamingType(((Element)obj).getAttributeValue(StorageData.NAME)) == myRoamingType;
      }
    }
  }

  private TObjectLongHashMap<String> loadVersions(List<Element> elements) {
    TObjectLongHashMap<String> result = new TObjectLongHashMap<String>();
    for (Element component : elements) {
      String name = component.getAttributeValue(ATTR_NAME);
      if (name != null) {
        long version = myLocalVersionProvider.getVersion(name);
        if (version > 0) {
          result.put(name, version);
        }
      }
    }
    return result;
  }

  @Override
  public void dispose() {
    myDisposed = true;
  }

  public void resetData(){
    myLoadedData = null;
  }

  @Override
  public void reload(@NotNull final Set<String> changedComponents) throws StateStorageException {
    final StorageData storageData = loadData(false, null);
    final StorageData oldLoadedData = myLoadedData;
    if (oldLoadedData != null) {
      Set<String> componentsToRetain = new THashSet<String>(oldLoadedData.myComponentStates.keySet());
      componentsToRetain.addAll(changedComponents);

      // add empty configuration tags for removed components
      for (String componentToRetain : componentsToRetain) {
        if (!storageData.myComponentStates.containsKey(componentToRetain) && myStorageComponentStates.containsKey(componentToRetain)) {
          Element emptyElement = new Element("component");
          LOG.info("Create empty component element for " + componentsToRetain);
          emptyElement.setAttribute(StorageData.NAME, componentToRetain);
          storageData.myComponentStates.put(componentToRetain, emptyElement);
        }
      }

      storageData.myComponentStates.keySet().retainAll(componentsToRetain);
    }

    myLoadedData = storageData;
  }

  private void filterOutOfDate(Element element) {
    if (myRemoteVersionProvider == null) {
      return;
    }

    Iterator<Element> iterator = element.getContent(new ElementFilter(StorageData.COMPONENT)).iterator();
    while (iterator.hasNext()) {
      String name = iterator.next().getAttributeValue(StorageData.NAME);
      long remoteVersion = myRemoteVersionProvider.getVersion(name);
      if (remoteVersion <= myLocalVersionProvider.getVersion(name)) {
        iterator.remove();
      }
      else {
        myLocalVersionProvider.changeVersion(name, remoteVersion);
      }
    }
  }

  @Nullable
  Element logComponents() {
    return mySession instanceof MySaveSession ? getElement(((MySaveSession)mySession).myStorageData) : null;
  }

  protected class RemoteComponentVersionProvider implements ComponentVersionProvider {
    protected TObjectLongHashMap<String> myProviderVersions;

    @Override
    public long getVersion(String name) {
      if (myProviderVersions == null) {
        loadProviderVersions();
      }
      return myProviderVersions == null ? -1 : myProviderVersions.get(name);
    }

    @Override
    public void changeVersion(String name, long version) {
      if (myProviderVersions == null) {
        loadProviderVersions();
      }
      if (myProviderVersions != null) {
        myProviderVersions.put(name, version);
      }
    }

    private void loadProviderVersions() {
      assert myStreamProvider != null;
      if (!myStreamProvider.isEnabled()) {
        return;
      }

      myProviderVersions = new TObjectLongHashMap<String>();
      for (RoamingType type : RoamingType.values()) {
        try {
          Document doc = StorageUtil.loadDocument(myStreamProvider.loadContent(myFileSpec + VERSION_FILE_SUFFIX, type));
          if (doc != null) {
            StateStorageManagerImpl.loadComponentVersions(myProviderVersions, doc);
          }
        }
        catch (IOException e) {
          LOG.debug(e);
        }
      }
    }
  }
}
