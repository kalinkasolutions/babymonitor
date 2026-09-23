using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Dal.Migrations
{
    /// <summary>
    /// Makes both sides of an account link cascade. While one did and the other did not, deleting
    /// the account that happened to sort second in the pair raised a foreign-key error, surfaced
    /// as an unexplained 500, and left an account its owner could not delete.
    ///
    /// SQLite cannot alter a foreign key, so EF rebuilds the table — which is why applying this
    /// logs a warning about <c>PRAGMA foreign_keys</c> not running inside a transaction. Nothing
    /// to act on: this migration already contains that one operation and nothing else, which is
    /// what the warning asks for.
    /// </summary>
    public partial class CascadeAccountLinkDeletes : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropForeignKey(
                name: "FK_AccountLinks_AspNetUsers_SecondUserId",
                table: "AccountLinks");

            migrationBuilder.AddForeignKey(
                name: "FK_AccountLinks_AspNetUsers_SecondUserId",
                table: "AccountLinks",
                column: "SecondUserId",
                principalTable: "AspNetUsers",
                principalColumn: "Id",
                onDelete: ReferentialAction.Cascade);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropForeignKey(
                name: "FK_AccountLinks_AspNetUsers_SecondUserId",
                table: "AccountLinks");

            migrationBuilder.AddForeignKey(
                name: "FK_AccountLinks_AspNetUsers_SecondUserId",
                table: "AccountLinks",
                column: "SecondUserId",
                principalTable: "AspNetUsers",
                principalColumn: "Id");
        }
    }
}
