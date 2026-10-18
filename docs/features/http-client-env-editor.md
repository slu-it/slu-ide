# HTTP Client environment editor

**Module:** `modules/http-client` — `HttpEnvironmentFileViewer`, package `dev.sluide.httpclient.envedit` (view
`EnvironmentFileView`, form `EnvironmentTable`, model `EnvDocument`). Decision:
[ADR 0057](../adr/0057-visual-environment-editor.md).

`http-client.env.json` and `http-client.private.env.json` ([http-client.md](http-client.md)) open in a split view: the
JSON source of the opened file on the left, a form of both files merged on the right. Other `.json` files stay with the
JSON viewer. The names are matched ignoring case.

## The view
- Toolbar: **+ Environment**, **Show values**, and **Source | Split | Visual** (Split when the file opens; the divider
  stays where it was when switching back to Split).
- The source is the normal code editor: saving, undo, change markers and changes on disk as for any code file.
- A warning row below the toolbar shows problems of the files (invalid JSON, values the form does not show), what the
  form could not do, and that git would commit the private file (not ignored), checked again on every git status
  change.

## The form
A tree table with one column per environment (public file's first, then the private file's):
- **Variables:** the merged keys of all environments. Objects and lists expand (list entries are named `[0]`, `[1]`,
  …). The Type column (Text, Number, Yes/No, Null, Object, List) of a variable is edited like a value (double-click,
  Enter or F2 shows the list of types); when values that cannot be converted would become empty values, the form asks
  first. Fields with a fixed set of values (grant type, client credentials, certificate format) show Choice. Rows
  whose environments disagree on the type show Mixed and are edited in the JSON source only.
- **Auth · \<id\>:** one section per `Security.Auth.<id>` with all its fields; the Type column sums it up, e.g.
  "OAuth2 · Client Credentials". Username and Password are greyed where the grant type is not Password, all fields
  where the environment lacks the configuration.
- **Client certificate:** the certificate and key file, their format, Has passphrase and Verify host certificate,
  shown once an environment has an `SSLConfiguration`.
- Values: "—" where the environment does not define the row, texts as they are, numbers as written, yes/no as a
  checkbox that changes the value at once, `null` in italics, objects and lists as `{n}` and `[n]`. Environments that
  are no objects in one of the files are greyed and not edited.
- Double-click, Enter or F2 edits a value: a field for texts and numbers (Enter or leaving the field commits, Escape
  cancels; a number that is not one is marked red and not committed), a list for choices (grant type, client
  credentials, certificate format), a field with **…** to choose a certificate file (written relative to the private
  file's folder when inside it, else absolute). Editing a value an environment lacks creates it there.

## The two files and the locks
- Each value has a lock at its end: open for the public file, closed for the private one ("overrides the public value"
  when the private file replaces one). Clicking it moves the value to the other file; inside a list the whole list
  moves. Certificate settings are private only.
- New values go where the model says: Client Secret, Password and certificate settings to the private file, everything
  else to the file of its object (the public one if it has the object).
- Private texts and numbers show as `••••••` until **Show values** is turned on; it is off whenever the file opens. A
  value being edited shows its real text; an empty text shows empty, so a secret not set yet does not look set.

## Actions
- Below the table: **+ Variable** (name and type; into the selected object of Variables, else at the top level; on a
  selected list it appends an entry, asking only its type), **− Remove** (the selected row from all environments, an
  auth configuration, or the client certificate of all environments; always asks), **+ Auth configuration…** (id,
  grant type Client Credentials or Password, environments, all checked first; Client Secret and Password go to the
  private file), **+ Client certificate** (for every environment without one; disabled when all have one).
- Context menu of a value: Remove from \<env\>, Make private / Make public, Remove row. Of an auth section: Remove
  configuration, Add to \<env\> for environments without it (with the grant type of an environment that has it). Of the
  certificate section: Add to / Remove from each environment. Of an environment's column header: Rename…, Remove….
- Dialogs check names as they are typed (taken names, empty ids, `$` names) and only enable OK for valid ones.

## Writing
- Changes of the opened file go through its editor, as one undo step, and leave it unsaved.
- Changes of the other file go into its tab, which is opened unselected if needed, and stay unsaved there. Its text is
  taken from that tab (with unsaved changes) or from disk, read again whenever the view is shown and after each change
  of the form. A missing other file is created as `{}` on disk the first time a value goes there.
- A form change is refused with "… changed meanwhile; nothing was changed, try again" when a text changed since the
  form read it: the opened file typed in while the form waits for a pause in typing (300 ms), or the other file's tab
  (or a missing other file that appeared with content). The form then shows the texts as they are.
- A file changed by the form is written in full with 2-space indentation, key order and number spelling kept, new keys
  appended: a file formatted otherwise is reformatted by its first form change. Top-level `$` keys and what the form
  does not show stay as they are.
- While either file is not valid JSON, the opened file is read-only, or the other file cannot be read, the form only
  shows and the warning row says why. If the other file's tab cannot be changed, nothing is changed.

## Limitations
- Comments make a file invalid JSON for the form, which is then read-only.
- Only the two files of the opened file's folder are edited together, not the nearest ones further up that requests
  would use.
- The other file's tab must be saved separately.
