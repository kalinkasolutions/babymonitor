using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Dal.Migrations
{
    /// <inheritdoc />
    public partial class AddDevicesAndAccountLinks : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "AccountLinks",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    CreatedAt = table.Column<DateTime>(type: "TEXT", nullable: false),
                    LastUpdatedAt = table.Column<DateTime>(type: "TEXT", nullable: false),
                    FirstUserId = table.Column<Guid>(type: "TEXT", nullable: false),
                    SecondUserId = table.Column<Guid>(type: "TEXT", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_AccountLinks", x => x.Id);
                    table.ForeignKey(
                        name: "FK_AccountLinks_AspNetUsers_FirstUserId",
                        column: x => x.FirstUserId,
                        principalTable: "AspNetUsers",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "FK_AccountLinks_AspNetUsers_SecondUserId",
                        column: x => x.SecondUserId,
                        principalTable: "AspNetUsers",
                        principalColumn: "Id");
                });

            migrationBuilder.CreateTable(
                name: "Devices",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    CreatedAt = table.Column<DateTime>(type: "TEXT", nullable: false),
                    LastUpdatedAt = table.Column<DateTime>(type: "TEXT", nullable: false),
                    UserId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Name = table.Column<string>(type: "TEXT", maxLength: 128, nullable: false),
                    PublicKey = table.Column<string>(type: "TEXT", maxLength: 1024, nullable: false),
                    LastSeenAt = table.Column<DateTime>(type: "TEXT", nullable: true),
                    BatteryPercent = table.Column<int>(type: "INTEGER", nullable: true),
                    IsCharging = table.Column<bool>(type: "INTEGER", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Devices", x => x.Id);
                    table.ForeignKey(
                        name: "FK_Devices_AspNetUsers_UserId",
                        column: x => x.UserId,
                        principalTable: "AspNetUsers",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "PairingTokens",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    CreatedAt = table.Column<DateTime>(type: "TEXT", nullable: false),
                    LastUpdatedAt = table.Column<DateTime>(type: "TEXT", nullable: false),
                    DeviceId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Code = table.Column<string>(type: "TEXT", maxLength: 32, nullable: false),
                    ExpiresAt = table.Column<DateTime>(type: "TEXT", nullable: false),
                    UsedAt = table.Column<DateTime>(type: "TEXT", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_PairingTokens", x => x.Id);
                    table.ForeignKey(
                        name: "FK_PairingTokens_Devices_DeviceId",
                        column: x => x.DeviceId,
                        principalTable: "Devices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_AccountLinks_FirstUserId_SecondUserId",
                table: "AccountLinks",
                columns: new[] { "FirstUserId", "SecondUserId" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_AccountLinks_SecondUserId",
                table: "AccountLinks",
                column: "SecondUserId");

            migrationBuilder.CreateIndex(
                name: "IX_Devices_UserId_PublicKey",
                table: "Devices",
                columns: new[] { "UserId", "PublicKey" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_PairingTokens_Code",
                table: "PairingTokens",
                column: "Code",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_PairingTokens_DeviceId",
                table: "PairingTokens",
                column: "DeviceId");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "AccountLinks");

            migrationBuilder.DropTable(
                name: "PairingTokens");

            migrationBuilder.DropTable(
                name: "Devices");
        }
    }
}
