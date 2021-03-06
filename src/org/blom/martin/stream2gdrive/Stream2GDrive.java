/*
 * Copyright (c) 2014 Martin Blom
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.blom.martin.stream2gdrive;

import java.io.*;
import java.util.*;
import org.apache.commons.cli.*;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.*;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.*;
import com.google.api.client.googleapis.extensions.java6.auth.oauth2.GooglePromptReceiver;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.media.*;
import com.google.api.client.http.*;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.client.util.BackOff;
import com.google.api.services.drive.*;
import com.google.api.services.drive.model.ParentReference;


public class Stream2GDrive {
    private static final String APP_NAME    = "Stream2GDrive";
    private static final String APP_VERSION = "1.3";

    private static final int EX_USAGE = 64;
    private static final int EX_IOERR = 74;

    public static void main(String[] args)
        throws Exception {
        Options opt = new Options();

        opt.addOption("?",  "help",      false, "Show usage.");
        opt.addOption("V",  "version",   false, "Print version information.");
        opt.addOption("v",  "verbose",   false, "Display progress status.");

        opt.addOption("p",  "parent",     true, "Operate inside this Google Drive folder instead of root.");
        opt.addOption("o",  "output",     true, "Override output/destination file name");
        opt.addOption("m",  "mime",       true, "Override guessed MIME type.");
        opt.addOption("C",  "chunk-size", true, "Set transfer chunk size, in MiB. Default is 10.0 MiB.");
        opt.addOption("r",  "auto-retry", false,"Enable automatic retry with exponential backoff in case of error.");

        opt.addOption(null, "oob",       false, "Provide OAuth authentication out-of-band.");

        try {
            CommandLine cmd = new GnuParser().parse(opt, args, false);
            args = cmd.getArgs();

            if (cmd.hasOption("version")) {
                String version = "?";
                String date    = "?";

                try {
                    Properties props = new Properties();
                    props.load(resource("/build.properties"));

                    version = props.getProperty("version", "?");
                    date    = props.getProperty("date", "?");
                }
                catch (Exception ignored) {}

                System.err.println(String.format("%s %s. Build %s (%s)",
                                                 APP_NAME, APP_VERSION, version, date));
                System.err.println();
            }

            if (cmd.hasOption("help")) {
                throw new ParseException(null);
            }

            if (args.length < 1) {
                if (cmd.hasOption("version")) {
                    return;
                }
                else {
                    throw new ParseException("<cmd> missing");
                }
            }

            String command = args[0];

            JsonFactory          jf = JacksonFactory.getDefaultInstance();
            HttpTransport        ht = GoogleNetHttpTransport.newTrustedTransport();
            GoogleClientSecrets gcs = GoogleClientSecrets.load(jf, resource("/client_secrets.json"));

            Set<String> scopes = new HashSet<String>();
            scopes.add(DriveScopes.DRIVE_FILE);
            scopes.add(DriveScopes.DRIVE_METADATA_READONLY);

            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(ht, jf, gcs, scopes)
                .setDataStoreFactory(new FileDataStoreFactory(appDataDir()))
                .build();

            VerificationCodeReceiver vcr = !cmd.hasOption("oob")
                ? new LocalServerReceiver()
                : new GooglePromptReceiver();

            Credential creds = new AuthorizationCodeInstalledApp(flow, vcr)
                .authorize("user");

            List<HttpRequestInitializer> hrilist = new ArrayList<HttpRequestInitializer>();
            hrilist.add( creds );

            if (cmd.hasOption("auto-retry")) {
                ExponentialBackOff.Builder backoffBuilder = new ExponentialBackOff.Builder()
                    .setInitialIntervalMillis(6*1000) // 6 seconds initial retry period
                    .setMaxElapsedTimeMillis(45*60*1000) // 45 minutes maximum total wait time
                    .setMaxIntervalMillis(15*60*1000) // 15 minute maximum interval
                    .setMultiplier(1.85)
                    .setRandomizationFactor(0.5);
                // Expected total waiting time before giving up = sum([6*1.85^i for i in range(10)])
                // ~= 55 minutes
                // Note that Google API's HttpRequest allows for up to 10 retry.
                hrilist.add( new ExponentialBackOffHttpRequestInitializer(backoffBuilder) );
            }
            HttpRequestInitializerStacker hristack = new HttpRequestInitializerStacker(hrilist);

            Drive client = new Drive.Builder(ht, jf, hristack)
                .setApplicationName(APP_NAME + "/" + APP_VERSION)
                .build();

            boolean verbose = cmd.hasOption("verbose");
            float chunkSize = Float.parseFloat(cmd.getOptionValue("chunk-size", "10.0"));

            String root = null;

            if (cmd.hasOption("parent")) {
                root = findWorkingDirectory(client, cmd.getOptionValue("parent"));
            }

            if (command.equals("get")) {
                String file;

                if (args.length < 2) {
                    throw new ParseException("<file> missing");
                }
                else if (args.length == 2) {
                    file = args[1];
                }
                else {
                    throw new ParseException("Too many arguments");
                }

                download(client, ht, root, file, cmd.getOptionValue("output", file),
                         verbose, chunkSize);
            }
            else if (command.equals("put")) {
                String file;

                if (args.length < 2) {
                    throw new ParseException("<file> missing");
                }
                else if (args.length == 2) {
                    file  = args[1];
                }
                else {
                    throw new ParseException("Too many arguments");
                }

                upload(client, file, root, cmd.getOptionValue("output", new File(file).getName()),
                       cmd.getOptionValue("mime", new javax.activation.MimetypesFileTypeMap().getContentType(file)),
                       verbose, chunkSize);
            }
            else if (command.equals("trash")) {
                String file;

                if (args.length < 2) {
                    throw new ParseException("<file> missing");
                }
                else if (args.length == 2) {
                    file = args[1];
                }
                else {
                    throw new ParseException("Too many arguments");
                }

                trash(client, root, file);
            }
            else if (command.equals("md5") || command.equals("list")) {
                if (args.length > 1) {
                    throw new ParseException("Too many arguments");
                }

                list(client, root, command.equals("md5"));
            }
            else {
                throw new ParseException("Invalid command: " + command);
            }
        }
        catch (ParseException ex) {
            PrintWriter   pw = new PrintWriter(System.err);
            HelpFormatter hf = new HelpFormatter();

            hf.printHelp(pw, 80, "stream2gdrive [OPTIONS] <cmd> [<options>]",
                         "  Commands: get <file>, list, md5, put <file>, trash <file>.",
                         opt, 2, 8,
                         "Use '-' as <file> for standard input.");

            if (ex.getMessage() != null && !ex.getMessage().isEmpty()) {
                pw.println();
                hf.printWrapped(pw, 80, String.format("Error: %s.", ex.getMessage()));
            }

            pw.flush();
            System.exit(EX_USAGE);
        }
        catch (NumberFormatException ex) {
            System.err.println("Invalid decimal number: " + ex.getMessage() + ".");
            System.exit(EX_USAGE);
        }
        catch (IOException ex) {
            System.err.println("I/O error: " + ex.getMessage() + ".");
            System.exit(EX_IOERR);
        }
    }

    public static void download(Drive client, HttpTransport ht, String root, String remote, String local, boolean progress, float chunkSize)
        throws IOException {
        OutputStream os;

        if (local.equals("-")) {
            os = System.out;
        }
        else {
            File file = new File(local);

            if (file.exists()) {
                throw new IOException(String.format("The local file '%s' already exists", file));
            }

            os = new FileOutputStream(file);
        }

        String link = findFile(client, remote, root == null ? "root" : root).getDownloadUrl();

        MediaHttpDownloader dl = new MediaHttpDownloader(ht, client.getRequestFactory().getInitializer());

        dl.setDirectDownloadEnabled(false);
        dl.setChunkSize(calcChunkSize(chunkSize));

        if (progress) {
            dl.setProgressListener(new ProgressListener());
        }

        dl.download(new GenericUrl(link), os);
    }

    public static void upload(Drive client, String local, String root, String remote, String mime, boolean progress, float chunkSize)
        throws IOException {

        com.google.api.services.drive.model.File meta = new com.google.api.services.drive.model.File();
        meta.setTitle(remote);
        meta.setMimeType(mime);

        if (root != null) {
            meta.setParents(Arrays.asList(new ParentReference().setId(root)));
        }

        AbstractInputStreamContent isc = local.equals("-")
            ? new StreamContent(meta.getMimeType(), System.in)
            : new FileContent(meta.getMimeType(), new File(local));

        Drive.Files.Insert insert = client.files().insert(meta, isc);

        MediaHttpUploader ul = insert.getMediaHttpUploader();
        ul.setDirectUploadEnabled(false);
        ul.setChunkSize(calcChunkSize(chunkSize));

        if (progress) {
            ul.setProgressListener(new ProgressListener());
        }

        // Streaming upload with GZip encoding has horrible performance!
        insert.setDisableGZipContent(isc instanceof StreamContent);

        insert.execute();
    }

    public static void list(Drive client, String root, boolean md5)
        throws IOException {
        com.google.api.services.drive.Drive.Files.List request = client.files().list()
            .setQ(String.format("'%s' in parents and mimeType!='application/vnd.google-apps.folder' and trashed=false",
                                root == null ? "root" : root))
            .setMaxResults(1000);

        do {
            com.google.api.services.drive.model.FileList files = request.execute();

            for (com.google.api.services.drive.model.File file : files.getItems()) {
                if (md5) {
                    System.out.println(String.format("%s *%s", file.getMd5Checksum(), file.getTitle()));
                }
                else {
                    System.out.println(String.format("%-29s %-19s %12d %s %s",
                                                     file.getMimeType(), file.getLastModifyingUserName(),
                                                     file.getFileSize(), file.getModifiedDate(), file.getTitle()));
                }
            }

            request.setPageToken(files.getNextPageToken());
        } while (request.getPageToken() != null && request.getPageToken().length() > 0);
    }

    public static void trash(Drive client, String root, String remote)
        throws IOException {

        client.files().trash(findFile(client, remote, root == null ? "root" : root).getId()).execute();
    }

    private static int calcChunkSize(float chunkSizeInMiB) {
        int multiple = Math.round(chunkSizeInMiB * 1024 * 1024 / MediaHttpUploader.MINIMUM_CHUNK_SIZE);

        return Math.max(1, multiple) * MediaHttpUploader.MINIMUM_CHUNK_SIZE;
    }

    private static String findWorkingDirectory(Drive client, String name)
        throws IOException {

        List<com.google.api.services.drive.model.File> folder = client.files().list()
            .setQ(String.format("title='%s' and mimeType='application/vnd.google-apps.folder' and trashed=false", name))
            .execute()
            .getItems();

        if (folder.size() == 0) {
            throw new IOException(String.format("Folder '%s' not found", name));
        }
        else if (folder.size() != 1) {
            throw new IOException(String.format("Folder '%s' matched more than one folder", name));
        }
        else {
            return folder.get(0).getId();
        }
    }

    private static com.google.api.services.drive.model.File findFile(Drive client, String name, String parent)
        throws IOException {
        List<com.google.api.services.drive.model.File> file = client.files().list()
            .setQ(String.format("title='%s' and '%s' in parents and mimeType!='application/vnd.google-apps.folder' and trashed=false", name, parent))
            .execute()
            .getItems();

        if (file.size() == 0) {
            throw new IOException(String.format("File '%s' not found", name));
        }
        else if (file.size() != 1) {
            throw new IOException(String.format("File '%s' matched more than one document", name));
        }
        else {
            return file.get(0);
        }
    }

    private static Reader resource(String name)
        throws IOException {
        return new InputStreamReader(Stream2GDrive.class.getResourceAsStream(name));
    }


    private static File appDataDir() {
        File root;
        String os = System.getProperty("os.name").toLowerCase();

        if (os.startsWith("windows")) {
            root = new File(System.getenv("AppData"));
        }
        else if (os.startsWith("mac os x")) {
            root = new File(System.getProperty("user.home"), "Library/Application Support");
        }
        else if (System.getenv("XDG_DATA_HOME") != null) {
            root = new File(System.getenv("XDG_DATA_HOME"));
        }
        else {
            root = new File(System.getProperty("user.home"), ".local/share");
        }

        return new File(root, APP_NAME);
    }


    private static class StreamContent
        extends AbstractInputStreamContent {
        private InputStream is;

        public StreamContent(String type, InputStream is) {
            super(type);
            this.is = is;
        }

        @Override public InputStream getInputStream() {
            return is;
        }

        @Override public boolean retrySupported() {
            return false;
        }

        @Override public long getLength() {
            return -1;
        }
    }

    private static class ProgressListener
        implements MediaHttpDownloaderProgressListener, MediaHttpUploaderProgressListener {

        private long startTime = System.currentTimeMillis();
        private long startByte = 0;

        @Override public void progressChanged(MediaHttpDownloader dl)
            throws IOException {
            switch (dl.getDownloadState()) {
                case MEDIA_IN_PROGRESS:
                    System.err.println(String.format("Downloaded %d MiB (%d %%). Current speed is %.1f MiB/s.",
                                                     dl.getNumBytesDownloaded() / 1024 / 1024,
                                                     (int) (dl.getProgress() * 100),
                                                     calcSpeed(dl.getNumBytesDownloaded())));
                    break;

                case MEDIA_COMPLETE:
                    System.err.println(String.format("Done! %d bytes downloaded.", dl.getNumBytesDownloaded()));
                    break;
            }
        }

        @Override public void progressChanged(MediaHttpUploader ul)
            throws IOException {
            switch (ul.getUploadState()) {
                case INITIATION_STARTED:
                    System.err.println("Preparing to upload ...");
                    break;

                case INITIATION_COMPLETE:
                    System.err.println("Starting upload ...");
                    break;

                case MEDIA_IN_PROGRESS:
                    try {
                        System.err.println(String.format("Uploaded %d of %d MiB (%d %%). Current speed is %.1f MiB/s.",
                                                         ul.getNumBytesUploaded() / 1024 / 1024,
                                                         ul.getMediaContent().getLength() / 1024 / 1024,
                                                         (int) (ul.getProgress() * 100),
                                                         calcSpeed(ul.getNumBytesUploaded())));
                    }
                    catch (IllegalArgumentException ignored) {
                        System.err.println(String.format("Uploaded %d MiB. Current speed is %.1f MiB/s.",
                                                         ul.getNumBytesUploaded() / 1024 / 1024,
                                                         calcSpeed(ul.getNumBytesUploaded())));
                    }

                    break;

                case MEDIA_COMPLETE:
                    System.err.println(String.format("Done! %d bytes uploaded.", ul.getNumBytesUploaded()));
                    break;
            }
        }

        private double calcSpeed(long currentPosition) {
            long   now = System.currentTimeMillis();
            double mib = (currentPosition - startByte) / (1.0 * 1024 * 1024);
            double sec = (now - startTime) / 1000.0;

            startByte = currentPosition;
            startTime = now;

            return mib / sec;
        }
    }

    private static class HttpRequestInitializerStacker
        implements HttpRequestInitializer {

        Iterable<HttpRequestInitializer> initializerList;

        public HttpRequestInitializerStacker( Iterable<HttpRequestInitializer> _initializerList ) {
            initializerList = _initializerList;
        }

        public void initialize(HttpRequest request) throws IOException {
            for ( HttpRequestInitializer hri : initializerList ) {
                hri.initialize( request );
            }
        }
    }

    private static class ExponentialBackOffHttpRequestInitializer
        implements HttpRequestInitializer {

        ExponentialBackOff.Builder backoffBuilder;

        public ExponentialBackOffHttpRequestInitializer( ExponentialBackOff.Builder _backoffBuilder ) {
            backoffBuilder = _backoffBuilder;
        }

        public void initialize(HttpRequest request) throws IOException {
            request.setIOExceptionHandler( new HttpBackOffIOExceptionHandler( backoffBuilder.build() ) );
            request.setUnsuccessfulResponseHandler( new HttpBackOffUnsuccessfulResponseHandler( backoffBuilder.build() ) );
        }
    }
}
