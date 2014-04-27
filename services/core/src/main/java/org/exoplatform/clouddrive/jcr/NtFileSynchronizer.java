/*
 * Copyright (C) 2003-2014 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.clouddrive.jcr;

import org.exoplatform.clouddrive.CloudDriveException;
import org.exoplatform.clouddrive.CloudFileAPI;
import org.exoplatform.clouddrive.CloudFileSynchronizer;
import org.exoplatform.clouddrive.SkipSyncException;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;

/**
 * Created by The eXo Platform SAS
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: NtFileSynchronizer.java 00000 Mar 21, 2014 pnedonosko $
 * 
 */
public class NtFileSynchronizer implements CloudFileSynchronizer {

  public static final String[] NODETYPES = new String[] { JCRLocalCloudDrive.NT_FILE,
      JCRLocalCloudDrive.NT_FOLDER      };

  protected static final Log   LOG       = ExoLogger.getLogger(NtFileSynchronizer.class);

  /**
   * 
   */
  public NtFileSynchronizer() {
  }

  /**
   * {@inheritDoc}
   */
  public String[] getSupportedNodetypes() {
    return NODETYPES;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean accept(Node file) throws RepositoryException, SkipSyncException {
    if (file.isNodeType(JCRLocalCloudDrive.NT_FILE) || file.isNodeType(JCRLocalCloudDrive.NT_FOLDER)) {
      return true;
    } else if (file.isNodeType(JCRLocalCloudDrive.NT_RESOURCE)) {
      throw new SkipSyncException("Skip synchronization of " + JCRLocalCloudDrive.NT_RESOURCE);
    }
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @Deprecated
  public boolean synchronize(Node file, CloudFileAPI api) throws CloudDriveException, RepositoryException {
    if (file.isNodeType(JCRLocalCloudDrive.ECD_CLOUDFILE)
        || file.isNodeType(JCRLocalCloudDrive.ECD_CLOUDDRIVE)) {
      // XXX do nothing for the moment, use drive sync for this purpose
      LOG.warn("Synchronization of already existing cloud file not supported for the moment. Use drive synchronization instead.");
      return false;
    } else {
      return create(file, api);
    }
  }

  /**
   * {@inheritDoc}
   */
  public boolean create(Node file, CloudFileAPI api) throws RepositoryException, CloudDriveException {
    String title = api.getTitle(file);
    Calendar created = file.getProperty("jcr:created").getDate();

    if (file.isNodeType(JCRLocalCloudDrive.NT_FILE)) { // use JCR, this node not yet a cloud file
      Node resource = file.getNode("jcr:content");
      String mimeType = resource.getProperty("jcr:mimeType").getString();
      Calendar modified = resource.getProperty("jcr:lastModified").getDate();
      // if (file.isNodeType(JCRLocalCloudDrive.EXO_DATETIME)) {
      // created = file.getProperty("exo:dateCreated").getDate();
      // modified = file.getProperty("exo:dateModified").getDate();
      // }
      InputStream data = resource.getProperty("jcr:data").getStream();

      try {
        api.createFile(file, created, modified, mimeType, data);
        resource.setProperty("jcr:data", JCRLocalCloudDrive.DUMMY_DATA); // empty data to zero string
        return true;
      } finally {
        try {
          data.close();
        } catch (IOException e) {
          LOG.warn("Error closing content stream of cloud file " + title + ": " + e.getMessage());
        }
      }
    } else if (file.isNodeType(JCRLocalCloudDrive.NT_FOLDER)) {
      api.createFolder(file, created);
      // traverse and create childs
      boolean result = true;
      for (NodeIterator childs = file.getNodes(); childs.hasNext();) {
        Node child = childs.nextNode();
        // TODO do we really need name cleanup? The child already is in JCR.
        // child = JCRLocalCloudDrive.cleanNode(child, api.getTitle(child));
        result &= create(child, api);
      }
      return result;
    } else {
      // it's smth not expected
      LOG.warn("Unexpected type of created node in nt:file or nt:folder hierarchy: "
          + file.getPrimaryNodeType().getName() + ". Location: " + file.getPath());
      return false;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean remove(String filePath, String fileId, boolean isFolder, CloudFileAPI api) throws CloudDriveException,
                                                                                           RepositoryException {
    if (isFolder) {
      api.removeFolder(fileId);
    } else {
      api.removeFile(fileId);
    }
    return true;
  }

  /**
   * {@inheritDoc}
   */
  public boolean trash(String filePath, String fileId, boolean isFolder, CloudFileAPI api) throws RepositoryException,
                                                                                          CloudDriveException {
    if (isFolder) {
      return api.trashFolder(fileId);
    } else {
      return api.trashFile(fileId);
    }
  }

  /**
   * {@inheritDoc}
   */
  public boolean untrash(Node file, CloudFileAPI api) throws RepositoryException, CloudDriveException {
    if (api.isFolder(file)) {
      return api.untrashFolder(file);
    } else {
      return api.untrashFile(file);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean update(Node file, CloudFileAPI api) throws CloudDriveException, RepositoryException {
    if (api.isFolder(file)) { // use API to get a type of given node, it is already a cloud file
      Calendar modified;
      if (file.isNodeType(JCRLocalCloudDrive.EXO_DATETIME)) {
        modified = file.getProperty("exo:dateModified").getDate();
      } else {
        modified = Calendar.getInstance(); // will be "now"
      }

      api.updateFolder(file, modified);
      // we don't traverse and update childs!
      return true;
    } else if (api.isFile(file)) {
      Node resource = file.getNode("jcr:content");
      Calendar modified = resource.getProperty("jcr:lastModified").getDate();

      api.updateFile(file, modified);
      return true;
    } else {
      // it's smth not expected
      LOG.warn("Unexpected type of updated node in nt:file or nt:folder hierarchy: "
          + file.getPrimaryNodeType().getName() + ". Location: " + file.getPath());
      return false;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean updateContent(Node file, CloudFileAPI api) throws CloudDriveException, RepositoryException {
    String title = api.getTitle(file);

    if (api.isFile(file)) { // use API, in accept() we already selected nt:file
      Node resource = file.getNode("jcr:content");
      String mimeType = resource.getProperty("jcr:mimeType").getString();
      Calendar modified = resource.getProperty("jcr:lastModified").getDate();
      InputStream data = resource.getProperty("jcr:data").getStream();

      try {
        api.updateFileContent(file, modified, mimeType, data);
        return true;
      } finally {
        try {
          data.close();
        } catch (IOException e) {
          LOG.warn("Error closing content stream of cloud file " + title + ": " + e.getMessage());
        }
      }
    } else {
      // it's smth not expected
      LOG.warn("Unexpected type of updated node (content) in nt:file or nt:folder hierarchy: "
          + file.getPrimaryNodeType().getName() + ". Location: " + file.getPath());
      return false;
    }
  }

}