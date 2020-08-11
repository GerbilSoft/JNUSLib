/****************************************************************************
 * Copyright (C) 2016-2019 Maschell
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 ****************************************************************************/
package de.mas.wiiu.jnus.implementations;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import de.mas.wiiu.jnus.Settings;
import de.mas.wiiu.jnus.entities.TMD.Content;
import de.mas.wiiu.jnus.interfaces.NUSDataProvider;
import de.mas.wiiu.jnus.interfaces.Parallelizable;
import de.mas.wiiu.jnus.utils.download.NUSDownloadService;
import lombok.Getter;

public class NUSDataProviderRemote implements NUSDataProvider, Parallelizable {
    @Getter private final int version;
    @Getter private final long titleID;
    private final NUSDownloadService downloadService;

    public NUSDataProviderRemote(int version, long titleID, NUSDownloadService downloadService) {
        this.version = version;
        this.titleID = titleID;
        this.downloadService = downloadService;
    }

    @Override
    public InputStream readRawContentAsStream(Content content, long fileOffsetBlock, long size) throws IOException {
        return downloadService.getInputStreamForURL(getRemoteURL(content), fileOffsetBlock, size);
    }

    private String getRemoteURL(Content content) {
        return String.format("%016x/%08X", titleID, content.getID());
    }

    Map<Integer, Optional<byte[]>> h3Hashes = new HashMap<>();

    @Override
    public Optional<byte[]> getContentH3Hash(Content content) throws IOException {
        Optional<byte[]> resOpt = h3Hashes.get(content.getID());
        if (resOpt == null) {
            NUSDownloadService downloadService = NUSDownloadService.getDefaultInstance();
            String url = getRemoteURL(content) + Settings.H3_EXTENTION;

            byte[] res = downloadService.downloadToByteArray(url);
            if (res == null || res.length == 0) {
                resOpt = Optional.empty();
            } else {
                resOpt = Optional.of(res);
            }
            h3Hashes.put(content.getID(), resOpt);
        }
        return resOpt;
    }

    Optional<byte[]> tmdCache = Optional.empty();

    @Override
    public Optional<byte[]> getRawTMD() throws IOException {
        if (!tmdCache.isPresent()) {
            long titleID = getTitleID();
            int version = getVersion();

            byte[] res = downloadService.downloadTMDToByteArray(titleID, version);

            if (res == null || res.length == 0) {
                return Optional.empty();
            }
            tmdCache = Optional.of(res);
        }
        return tmdCache;
    }

    Optional<byte[]> ticketCache = Optional.empty();

    @Override
    public Optional<byte[]> getRawTicket() throws IOException {
        if (!ticketCache.isPresent()) {
            byte[] res = downloadService.downloadTicketToByteArray(titleID);
            if (res == null || res.length == 0) {
                return Optional.empty();
            }
            ticketCache = Optional.of(res);
        }
        return ticketCache;
    }

    @Override
    public Optional<byte[]> getRawCert() throws IOException {
        return Optional.empty();
    }

    @Override
    public void cleanup() throws IOException {
        // We don't need this
    }
}
